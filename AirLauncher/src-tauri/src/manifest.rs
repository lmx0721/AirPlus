/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 下载清单（manifest）：实例目录里的一份 JSON，记录
 *   1) 下过哪些 AirPlus 客户端版本（tag、文件名、sha1、大小、时间、是否生效）
 *   2) 装过哪些模组（文件名、来源、项目/版本号、sha1、大小、时间）
 *
 * 为什么不直接扫 mods 目录：
 * - 扫目录只能看到「现在有什么」，看不到来源、版本号和安装时间；
 * - 客户端旧版本会被 remove_other_clients 删掉，但「下过哪些版本」仍需要留档；
 * - sha1 是重装 / 校验 / 打包整合包时的唯一凭据，事后再算既慢又可能文件已损坏。
 *
 * 文件位置：<instance>/launcher-manifest.json（与 mods 同级，方便直接打开查看）
 */
use crate::error::{LauncherError, Result};
use crate::paths;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// 记录文件名（放在实例根目录）
pub const FILE_NAME: &str = "launcher-manifest.json";

/// 当前记录格式版本。结构变更时递增，读取侧据此做迁移。
const SCHEMA_VERSION: u32 = 1;

/// 串行化「读 → 改 → 写」，避免并发下载完成时互相覆盖。
/// 写入频率很低（每个文件一次），不做缓存。
static LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    /// 记录格式版本
    pub version: u32,
    /// 下载过的客户端 jar，按文件名去重
    pub clients: Vec<ClientRecord>,
    /// 安装过的模组，按文件名去重
    pub mods: Vec<ModRecord>,
}

impl Default for Manifest {
    fn default() -> Self {
        Self {
            version: SCHEMA_VERSION,
            clients: Vec::new(),
            mods: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientRecord {
    pub file_name: String,
    /// GitHub release tag（如 b4）
    pub tag: Option<String>,
    pub size: u64,
    pub sha1: Option<String>,
    pub url: Option<String>,
    /// ISO8601 UTC
    pub installed_at: String,
    /// 是否是 mods 里当前唯一生效的客户端
    pub active: bool,
    /// 文件是否还在 mods 目录（被清理掉的历史版本为 false）
    pub present: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModRecord {
    pub file_name: String,
    pub size: u64,
    pub sha1: Option<String>,
    /// modrinth | optifine | manual
    pub source: String,
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub version_number: Option<String>,
    pub url: Option<String>,
    pub installed_at: String,
    /// 文件是否还在 mods 目录
    pub present: bool,
}

/// 下载完成后要写入清单的类型。由下载管线在成功时回传，
/// 这样 `start_task` 那种「立即返回 task_id」的后台任务也能留下记录。
#[derive(Debug, Clone)]
pub enum Origin {
    /// AirPlus 客户端本体
    Client { tag: Option<String> },
    /// 模组：来源 + 可选的 Modrinth 元数据
    Mod {
        source: String,
        project_id: Option<String>,
        version_id: Option<String>,
        version_number: Option<String>,
    },
}

impl Origin {
    pub fn client(tag: Option<String>) -> Self {
        Origin::Client { tag }
    }

    pub fn modrinth(
        project_id: Option<String>,
        version_id: Option<String>,
        version_number: Option<String>,
    ) -> Self {
        Origin::Mod {
            source: "modrinth".into(),
            project_id,
            version_id,
            version_number,
        }
    }

    pub fn optifine(version: Option<String>) -> Self {
        Origin::Mod {
            source: "optifine".into(),
            project_id: None,
            version_id: None,
            version_number: version,
        }
    }

    pub fn manual() -> Self {
        Origin::Mod {
            source: "manual".into(),
            project_id: None,
            version_id: None,
            version_number: None,
        }
    }
}

// ---------- 读写 ----------

pub fn path() -> PathBuf {
    paths::get().instance_dir.join(FILE_NAME)
}

fn read_from(p: &Path) -> Manifest {
    let Ok(bytes) = std::fs::read(p) else {
        return Manifest::default();
    };
    match serde_json::from_slice::<Manifest>(&bytes) {
        Ok(m) => m,
        Err(e) => {
            // 文件损坏时不要让启动器起不来：留个备份再重建。
            eprintln!("下载清单解析失败({e})，已重建：{}", p.display());
            let _ = std::fs::rename(p, p.with_extension("json.bak"));
            Manifest::default()
        }
    }
}

/// 先写临时文件再原子替换，避免进程被杀时留下半截 JSON
fn save_to(p: &Path, m: &Manifest) -> Result<()> {
    let json = serde_json::to_vec_pretty(m)?;
    let tmp = p.with_extension("json.tmp");
    std::fs::write(&tmp, &json)?;
    std::fs::rename(&tmp, p)?;
    Ok(())
}

fn mutate<T>(f: impl FnOnce(&mut Manifest) -> T) -> T {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let p = path();
    let mut m = read_from(&p);
    let out = f(&mut m);
    if let Err(e) = save_to(&p, &m) {
        eprintln!("写入下载清单失败: {e}");
    }
    out
}

// ---------- 记录 ----------

/// 下载成功后的统一入口。
pub fn record_download(origin: &Origin, file_name: &str, url: &str, size: u64, sha1: String) {
    let now = now_iso();
    mutate(|m| match origin {
        Origin::Client { tag } => {
            // 同一时间只有一个生效客户端
            for c in m.clients.iter_mut() {
                c.active = false;
            }
            let rec = ClientRecord {
                file_name: file_name.to_string(),
                tag: tag.clone(),
                size,
                sha1: Some(sha1),
                url: Some(url.to_string()),
                installed_at: now.clone(),
                active: true,
                present: true,
            };
            match m.clients.iter_mut().find(|c| c.file_name == file_name) {
                Some(existing) => *existing = rec,
                None => m.clients.push(rec),
            }
            // 最新的排前面，便于前端直接展示
            m.clients.sort_by(|a, b| b.installed_at.cmp(&a.installed_at));
        }
        Origin::Mod {
            source,
            project_id,
            version_id,
            version_number,
        } => {
            let rec = ModRecord {
                file_name: file_name.to_string(),
                size,
                sha1: Some(sha1),
                source: source.clone(),
                project_id: project_id.clone(),
                version_id: version_id.clone(),
                version_number: version_number.clone(),
                url: Some(url.to_string()),
                installed_at: now.clone(),
                present: true,
            };
            match m.mods.iter_mut().find(|r| r.file_name == file_name) {
                Some(existing) => *existing = rec,
                None => m.mods.push(rec),
            }
            m.mods
                .sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase()));
        }
    });
}

/// 删除模组后同步移除记录
pub fn forget_mod(file_name: &str) {
    mutate(|m| {
        let before = m.mods.len();
        m.mods.retain(|r| r.file_name != file_name);
        if m.mods.len() != before {
            println!("下载清单：已移除模组记录 {file_name}");
        }
    });
}

/// 切换生效客户端时更新标记（不新增记录）
pub fn set_active_client(file_name: &str) {
    mutate(|m| {
        for c in m.clients.iter_mut() {
            c.active = c.file_name == file_name;
            if c.active {
                c.present = true;
            }
        }
    });
}

/// 与 mods 目录对齐：
/// - 刷新 present / active 标记
/// - 补录历史上就存在但没进过清单的 jar（升级前装的，或手动丢进目录的）
/// - 文件被替换（大小变化）时重算 sha1
///
/// 只对新文件/变化文件算哈希，正常情况下每次启动几乎没有额外开销。
pub fn sync() -> Manifest {
    let mods = paths::get().mods_dir.clone();
    let current = crate::client::find_client_jar(&mods)
        .and_then(|p| p.file_name().map(|n| n.to_string_lossy().to_string()));

    // 目录扫描放在写锁外面
    let mut files: Vec<(String, u64, String)> = Vec::new();
    if let Ok(entries) = std::fs::read_dir(&mods) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.to_lowercase().ends_with(".jar") {
                continue;
            }
            let Ok(meta) = entry.metadata() else {
                continue;
            };
            files.push((name, meta.len(), iso_from(meta.modified().ok())));
        }
    }

    mutate(|m| {
        for (name, size, mtime) in &files {
            let path = mods.join(name);
            if crate::client::is_client_jar(name) {
                let active = current.as_deref() == Some(name.as_str());
                match m.clients.iter_mut().find(|c| &c.file_name == name) {
                    Some(c) => {
                        c.present = true;
                        c.active = active;
                        // 老记录可能没 tag（早期只记了文件名），顺手补上
                        if c.tag.is_none() {
                            c.tag = tag_from_file_name(name);
                        }
                        refresh_hash(&path, *size, &mut c.size, &mut c.sha1);
                    }
                    None => m.clients.push(ClientRecord {
                        file_name: name.clone(),
                        tag: tag_from_file_name(name),
                        size: *size,
                        sha1: crate::download::sha1_file_sync(&path).ok(),
                        url: None,
                        installed_at: mtime.clone(),
                        active,
                        present: true,
                    }),
                }
            } else {
                match m.mods.iter_mut().find(|r| &r.file_name == name) {
                    Some(r) => {
                        r.present = true;
                        refresh_hash(&path, *size, &mut r.size, &mut r.sha1);
                    }
                    None => m.mods.push(ModRecord {
                        file_name: name.clone(),
                        size: *size,
                        sha1: crate::download::sha1_file_sync(&path).ok(),
                        source: "manual".into(),
                        project_id: None,
                        version_id: None,
                        version_number: None,
                        url: None,
                        installed_at: mtime.clone(),
                        present: true,
                    }),
                }
            }
        }

        // 目录里已经没有的文件：只改状态，不删记录（历史版本仍要能查到）
        let exists = |name: &str| files.iter().any(|(n, _, _)| n == name);
        for c in m.clients.iter_mut() {
            if !exists(&c.file_name) {
                c.present = false;
                c.active = false;
            }
        }
        for r in m.mods.iter_mut() {
            if !exists(&r.file_name) {
                r.present = false;
            }
        }

        m.clients
            .sort_by(|a, b| b.installed_at.cmp(&a.installed_at));
        m.mods
            .sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase()));
        m.clone()
    })
}

/// 从文件名推断 release 标签：AirPlus-b4.jar -> Some("b4")。
/// 补录历史文件时 GitHub 已经不记得当时下的哪个 tag，但文件名里带着。
pub fn tag_from_file_name(name: &str) -> Option<String> {
    let lower = name.to_lowercase();
    let rest = lower.strip_prefix("airplus-")?.strip_suffix(".jar")?;
    if rest.is_empty() {
        None
    } else {
        Some(rest.to_string())
    }
}

/// 大小变了或还没算过哈希时，刷新大小与 sha1
fn refresh_hash(path: &Path, size: u64, rec_size: &mut u64, rec_sha1: &mut Option<String>) {
    if *rec_size != size || rec_sha1.is_none() {
        *rec_size = size;
        *rec_sha1 = crate::download::sha1_file_sync(path).ok();
    }
}

// ---------- 命令 ----------

/// 读取（并顺带与磁盘对齐）下载清单。文件 IO 下沉到阻塞线程池。
#[tauri::command]
pub async fn manifest_get() -> Result<Manifest> {
    tauri::async_runtime::spawn_blocking(sync)
        .await
        .map_err(|e| LauncherError::Message(format!("读取下载记录失败: {e}")))
}

// ---------- 时间 ----------

/// 当前时间（ISO8601 UTC）。只为写清单服务，不引入 chrono。
fn now_iso() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    iso_from_secs(secs)
}

/// 文件修改时间 → ISO8601（补录历史文件时用真实时间，而不是「现在」）
fn iso_from(t: Option<std::time::SystemTime>) -> String {
    let secs = t
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    if secs == 0 {
        now_iso()
    } else {
        iso_from_secs(secs)
    }
}

fn iso_from_secs(secs: i64) -> String {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, mo, d) = civil_from_days(days);
    format!(
        "{y:04}-{mo:02}-{d:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

/// Howard Hinnant 的 civil_from_days：把 Unix 天数换算成 (年, 月, 日)
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as i64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}
