/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * Mojang 版本元数据与资源下载。实现参考 CCBlueX/LiquidLauncher（GPL-3.0）：
 * version manifest -> version profile（含 inheritsFrom 合并）->
 * libraries / natives / assets / client jar 下载与 classpath 组装。
 */
use crate::download;
use crate::download::DownloadProgress;
use crate::error::{LauncherError, Result};
use crate::events;
use crate::paths;
use futures_util::{stream, StreamExt, TryStreamExt};
use serde::Deserialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager};
use tokio_util::sync::CancellationToken;

pub const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
pub const DEFAULT_LIBRARY_REPO: &str = "https://libraries.minecraft.net/";
pub const ASSET_BASE: &str = "https://resources.download.minecraft.net/";

/// Settings.mirror（如 BMCLAPI）存在时替换官方源前缀
pub fn map_url(url: &str, mirror: Option<&str>) -> String {
    match mirror {
        Some(m) if !m.is_empty() => url
            .replace("https://piston-meta.mojang.com", m)
            .replace("https://launchermeta.mojang.com", m)
            .replace("https://libraries.minecraft.net", &format!("{m}/maven"))
            .replace("https://resources.download.minecraft.net", &format!("{m}/assets"))
            .replace("https://maven.minecraftforge.net", &format!("{m}/maven")),
        _ => url.to_string(),
    }
}

// ---------- manifest / profile ----------

#[derive(Deserialize)]
pub struct VersionManifest {
    pub versions: Vec<ManifestVersion>,
}

#[derive(Deserialize)]
pub struct ManifestVersion {
    pub id: String,
    #[serde(rename = "type")]
    pub version_type: String,
    pub url: String,
}

pub async fn fetch_manifest(token: &CancellationToken) -> Result<VersionManifest> {
    let fut = crate::net::get().get(MANIFEST_URL).send();
    let resp = token
        .run_until_cancelled(fut)
        .await
        .ok_or(LauncherError::Cancelled)?
        .map_err(|e| LauncherError::Message(format!("获取版本清单失败: {e}")))?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("版本清单请求失败: {e}")))?;
    resp.json()
        .await
        .map_err(|e| LauncherError::Message(format!("版本清单解析失败: {e}")))
}

/// 版本 JSON。1.8.9 与 Forge 均为 V14 格式（minecraftArguments 字符串）。
#[derive(Deserialize, Clone)]
pub struct VersionProfile {
    pub id: String,
    #[serde(rename = "inheritsFrom")]
    pub inherits_from: Option<String>,
    #[serde(rename = "mainClass")]
    pub main_class: Option<String>,
    #[serde(rename = "minecraftArguments")]
    pub minecraft_arguments: Option<String>,
    #[serde(rename = "assetIndex")]
    pub asset_index: Option<AssetIndexLocation>,
    pub downloads: Option<Downloads>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(rename = "type")]
    pub version_type: Option<String>,
    pub jar: Option<String>,
}

impl VersionProfile {
    pub fn load_file(path: &Path) -> Result<VersionProfile> {
        let bytes = std::fs::read(path)?;
        serde_json::from_slice(&bytes)
            .map_err(|e| LauncherError::Message(format!("版本 JSON 解析失败 {}: {e}", path.display())))
    }

    /// 子版本（forge）优先，父版本（vanilla）补充；库按 group:artifact(:classifier) 去重
    pub fn merge(&mut self, parent: VersionProfile) {
        if self.main_class.is_none() {
            self.main_class = parent.main_class;
        }
        if self.minecraft_arguments.is_none() {
            self.minecraft_arguments = parent.minecraft_arguments;
        }
        if self.asset_index.is_none() {
            self.asset_index = parent.asset_index;
        }
        if self.downloads.is_none() {
            self.downloads = parent.downloads;
        }
        if self.version_type.is_none() {
            self.version_type = parent.version_type;
        }

        let mut map: HashMap<String, Library> = self
            .libraries
            .drain(..)
            .map(|l| (lib_key(&l.name), l))
            .collect();
        for pl in parent.libraries {
            map.entry(lib_key(&pl.name)).or_insert(pl);
        }
        self.libraries = map.into_values().collect();
    }
}

/// group:artifact + classifier（有则带上），同坐标的库视为重复
fn lib_key(name: &str) -> String {
    let parts: Vec<&str> = name.split(':').collect();
    match parts.len() {
        3 => format!("{}:{}", parts[0], parts[1]),
        4 => format!("{}:{}:{}", parts[0], parts[1], parts[3]),
        _ => name.to_string(),
    }
}

/// maven 坐标 -> 相对路径
pub fn maven_path(name: &str, ext: &str) -> String {
    // 老版本 JSON 的 natives classifier 会写成 natives-windows-${arch}，
    // 不替换就会拼出 ...-natives-windows-$%7Barch%7D.jar 这种必然 404 的地址。
    let name = resolve_placeholders(name);
    let parts: Vec<&str> = name.split(':').collect();
    if parts.len() < 3 {
        return name.replace(':', "/");
    }
    // group:artifact:version[:classifier]
    let (g, a, v) = (parts[0], parts[1], parts[2]);
    let file = match parts.get(3) {
        Some(c) => format!("{}-{}-{}.{}", a, v, c, ext),
        None => format!("{}-{}.{}", a, v, ext),
    };
    format!("{}/{}/{}/{}", g.replace('.', "/"), a, v, file)
}

/// 本机架构在 Minecraft 老版本 natives 命名里的取值（1.8.9 用 64 / 32）
fn native_arch() -> &'static str {
    if cfg!(target_pointer_width = "64") {
        "64"
    } else {
        "32"
    }
}

/// 替换版本 JSON 里的 maven 占位符，目前只有 `${arch}`
fn resolve_placeholders(s: &str) -> String {
    s.replace("${arch}", native_arch())
}

// ---------- library ----------

#[derive(Deserialize, Clone)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub rules: Vec<Rule>,
    pub url: Option<String>,
    pub natives: Option<HashMap<String, String>>,
    pub downloads: Option<LibraryDownloads>,
}

#[derive(Deserialize, Clone)]
pub struct Rule {
    pub action: String, // allow | disallow
    pub os: Option<OsRule>,
}

#[derive(Deserialize, Clone)]
pub struct OsRule {
    pub name: Option<String>,
    pub arch: Option<String>,
    pub version: Option<String>,
}

#[derive(Deserialize, Clone)]
pub struct LibraryDownloads {
    pub artifact: Option<LibraryArtifact>,
    pub classifiers: Option<HashMap<String, LibraryArtifact>>,
}

#[derive(Deserialize, Clone)]
pub struct LibraryArtifact {
    pub path: String,
    pub sha1: String,
    pub url: String,
}

#[derive(Deserialize, Clone)]
pub struct LibraryDownloadInfo {
    pub path: String,
    pub sha1: Option<String>,
    pub url: String,
}

/// 系统版本 (major, minor, build)，用于匹配 os.version 规则。
/// 直接调 ntdll!RtlGetVersion：GetVersionEx 在未声明 Win10 兼容清单时只会返回 6.2，
/// 而这里拿到的是真实版本，且无需额外依赖。
#[cfg(windows)]
fn windows_version() -> Option<(u32, u32, u32)> {
    use std::sync::OnceLock;

    #[repr(C)]
    struct OsVersionInfoW {
        size: u32,
        major: u32,
        minor: u32,
        build: u32,
        platform_id: u32,
        csd_version: [u16; 128],
    }

    unsafe extern "system" {
        fn RtlGetVersion(info: *mut OsVersionInfoW) -> i32; // NTSTATUS, 0 = 成功
    }

    static CACHE: OnceLock<Option<(u32, u32, u32)>> = OnceLock::new();
    *CACHE.get_or_init(|| unsafe {
        let mut info = OsVersionInfoW {
            size: std::mem::size_of::<OsVersionInfoW>() as u32,
            major: 0,
            minor: 0,
            build: 0,
            platform_id: 0,
            csd_version: [0; 128],
        };
        if RtlGetVersion(&mut info) == 0 {
            Some((info.major, info.minor, info.build))
        } else {
            None
        }
    })
}

#[cfg(not(windows))]
fn windows_version() -> Option<(u32, u32, u32)> {
    None
}

/// os.version 是正则，按 Java 的 os.version 语义（"10.0"）与完整版本都试一次
fn version_matches(pattern: &str) -> bool {
    let Some((major, minor, build)) = windows_version() else {
        return true; // 拿不到版本时不据此排除库
    };
    let Ok(re) = regex::Regex::new(pattern) else {
        return true; // 正则非法，不做过滤
    };
    re.is_match(&format!("{major}.{minor}.{build}")) || re.is_match(&format!("{major}.{minor}"))
}

/// 单条规则是否适用于当前环境（Windows）
fn rule_applies(rule: &Rule) -> bool {
    let Some(os) = &rule.os else {
        return true;
    };
    if let Some(name) = &os.name {
        if name != "windows" {
            return false;
        }
    }
    if let Some(arch) = &os.arch {
        let ok = match arch.as_str() {
            "x86" => native_arch() == "32",
            "x86_64" => native_arch() == "64",
            other => other == native_arch(),
        };
        if !ok {
            return false;
        }
    }
    if let Some(pattern) = &os.version {
        if !version_matches(pattern) {
            return false;
        }
    }
    true
}

/// Mojang 规则语义：无规则 = 允许；否则从「不允许」开始，
/// 只有命中当前环境的规则才会改写结果（最后一条命中者说了算）。
pub fn rules_allow(rules: &[Rule]) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if rule_applies(rule) {
            allowed = rule.action == "allow";
        }
    }
    allowed
}

impl Library {
    /// 库 jar 的下载信息；natives 库返回 None（不走 classpath）
    pub fn download_info(&self) -> Option<LibraryDownloadInfo> {
        if let Some(a) = self.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
            return Some(LibraryDownloadInfo {
                path: a.path.clone(),
                sha1: Some(a.sha1.clone()),
                url: resolve_placeholders(&a.url),
            });
        }
        let base = self.url.as_deref().unwrap_or(DEFAULT_LIBRARY_REPO);
        let path = maven_path(&self.name, "jar");
        Some(LibraryDownloadInfo {
            path: path.clone(),
            sha1: None,
            url: format!("{}{}", base, path),
        })
    }

    /// 本机 natives classifier 的下载信息（仅 windows）
    pub fn natives_info(&self) -> Option<LibraryDownloadInfo> {
        // 1.8.9 的 twitch 平台库 classifier 写作 natives-windows-${arch}，
        // 必须替换成本机架构，否则既查不到 classifiers 也拼不出正确 URL。
        let classifier = resolve_placeholders(self.natives.as_ref()?.get("windows")?);
        if let Some(a) = self
            .downloads
            .as_ref()
            .and_then(|d| d.classifiers.as_ref())
            .and_then(|c| c.get(&classifier))
        {
            return Some(LibraryDownloadInfo {
                path: a.path.clone(),
                sha1: Some(a.sha1.clone()),
                url: resolve_placeholders(&a.url),
            });
        }
        // 老版本 JSON 没有 downloads 段：按 maven 坐标 + classifier 推导
        let name_with_classifier = format!("{}:{}", self.name, classifier);
        let path = maven_path(&name_with_classifier, "jar");
        Some(LibraryDownloadInfo {
            path: path.clone(),
            sha1: None,
            url: format!(
                "{}{}",
                self.url.as_deref().unwrap_or(DEFAULT_LIBRARY_REPO),
                path
            ),
        })
    }
}

// ---------- assets ----------

#[derive(Deserialize, Clone)]
pub struct AssetIndexLocation {
    pub id: String,
    pub sha1: String,
    pub url: String,
}

#[derive(Deserialize)]
pub struct AssetIndex {
    pub objects: HashMap<String, AssetObject>,
}

#[derive(Deserialize, Clone)]
pub struct AssetObject {
    pub hash: String,
    /// 文件字节数，仅用于汇总整体进度（个别老索引可能没有该字段）
    #[serde(default)]
    pub size: u64,
}

#[derive(Deserialize, Clone)]
pub struct Downloads {
    pub client: Option<ClientDownload>,
}

#[derive(Deserialize, Clone)]
pub struct ClientDownload {
    pub sha1: String,
    pub url: String,
    pub size: i64,
}

// ---------- 下载保障（ensure_*） ----------

fn emit_log(app: &AppHandle, msg: &str) {
    let _ = app.emit(events::LAUNCH_LOG, msg.to_string());
}

/// 下载全部缺失的库，返回 classpath 条目（不含 natives），并把 natives 解压到位
pub async fn ensure_libraries(
    app: &AppHandle,
    profile: &VersionProfile,
    token: &CancellationToken,
) -> Result<Vec<PathBuf>> {
    let libs_dir = paths::get().libraries_dir();
    let natives_dir = paths::get().natives_dir("1.8.9");
    tokio::fs::create_dir_all(&natives_dir).await?;

    let mirror = app.state::<crate::state::AppState>().settings_snapshot().mirror;
    let mirror = mirror.as_deref();
    let task_id = "launch-libs".to_string();
    let total = profile.libraries.len();
    let mut done = 0usize;

    let mut classpath: Vec<PathBuf> = Vec::new();
    let mut native_zips: Vec<PathBuf> = Vec::new();

    for lib in &profile.libraries {
        if !rules_allow(&lib.rules) {
            continue;
        }
        // natives：下载 + 解压（不进 classpath）
        if lib.natives.is_some() {
            if let Some(info) = lib.natives_info() {
                let dest = libs_dir.join(&info.path);
                if !dest.exists() {
                    download::fetch_to(
                        app, &task_id, &lib.name,
                        &map_url(&info.url, mirror), &dest,
                        info.sha1.as_deref(), token,
                    ).await?;
                }
                native_zips.push(dest);
            }
            continue;
        }
        if let Some(info) = lib.download_info() {
            let dest = libs_dir.join(&info.path);
            if !dest.exists() {
                download::fetch_to(
                    app, &task_id, &lib.name,
                    &map_url(&info.url, mirror), &dest,
                    info.sha1.as_deref(), token,
                ).await?;
            }
            classpath.push(dest);
        }
        done += 1;
        emit_log(app, &format!("库 {done}/{total}: {}", lib.name));
    }

    // 解压 natives（同步 zip，量小放阻塞线程）
    let zips = std::mem::take(&mut native_zips);
    let natives_dir_c = natives_dir.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<()> {
        for z in zips {
            extract_zip_to(&z, &natives_dir_c)?;
        }
        Ok(())
    })
    .await
    .map_err(|e| LauncherError::Message(format!("natives 解压任务失败: {e}")))??;

    Ok(classpath)
}

/// 资源索引读取（半截/损坏文件会返回 Err，由调用方重下）
async fn read_asset_index(path: &Path) -> Result<AssetIndex> {
    let bytes = tokio::fs::read(path).await?;
    serde_json::from_slice(&bytes)
        .map_err(|e| LauncherError::Message(format!("资源索引解析失败: {e}")))
}

/// 资源下载：对齐 LiquidLauncher 的做法
/// 1. 索引与对象都以「文件存在」为完成判据（文件名本身就是 sha1，无需再算一遍）
/// 2. 上千个对象走并发流下载，并发度跟随设置
/// 3. 单个对象失败不中断整体，只记录下来，下次启动自动补齐
///
/// 与 LiquidLauncher 的差异：仍复用统一下载管线（保留 .part 续传与 sha1 校验），
/// 并把所有对象汇总成**一个**进度条，避免上千个任务刷爆任务面板。
pub async fn ensure_assets(
    app: &AppHandle,
    profile: &VersionProfile,
    token: &CancellationToken,
) -> Result<String> {
    let index = profile
        .asset_index
        .as_ref()
        .ok_or_else(|| LauncherError::Message("版本 JSON 缺少 assetIndex".into()))?;

    let assets_dir = paths::get().assets_dir();
    let indexes_dir = assets_dir.join("indexes");
    let objects_dir = assets_dir.join("objects");
    tokio::fs::create_dir_all(&indexes_dir).await?;
    tokio::fs::create_dir_all(&objects_dir).await?;

    let snapshot = app.state::<crate::state::AppState>().settings_snapshot();
    let mirror = snapshot.mirror.clone();

    let index_file = indexes_dir.join(format!("{}.json", index.id));
    if !index_file.exists() {
        download::fetch_to(
            app,
            "launch-assets",
            &format!("资源索引 {}", index.id),
            &map_url(&index.url, mirror.as_deref()),
            &index_file,
            Some(&index.sha1),
            token,
        )
        .await?;
    }

    // 上次中断可能留下半截索引：解析失败就删掉重下一次
    let parsed = match read_asset_index(&index_file).await {
        Ok(p) => p,
        Err(e) => {
            emit_log(app, &format!("资源索引损坏，重新下载：{e}"));
            let _ = tokio::fs::remove_file(&index_file).await;
            download::fetch_to(
                app,
                "launch-assets",
                &format!("资源索引 {}", index.id),
                &map_url(&index.url, mirror.as_deref()),
                &index_file,
                Some(&index.sha1),
                token,
            )
            .await?;
            read_asset_index(&index_file).await?
        }
    };

    // 只挑缺失的：文件名即 sha1，存在就认为完整
    let mut missing: Vec<AssetObject> = Vec::new();
    for obj in parsed.objects.into_values() {
        if obj.hash.len() < 2 {
            continue;
        }
        let dest = objects_dir.join(&obj.hash[..2]).join(&obj.hash);
        if !dest.exists() {
            missing.push(obj);
        }
    }

    let total_files = missing.len() as u64;
    if total_files == 0 {
        emit_log(app, "游戏资源完整，无需下载");
        return Ok(index.id.clone());
    }

    let total_bytes: u64 = missing.iter().map(|o| o.size).sum();
    emit_log(
        app,
        &format!(
            "游戏资源缺失 {total_files} 个（约 {} MB），并发下载中…",
            (total_bytes / 1024 / 1024).max(1)
        ),
    );

    let concurrency = snapshot.download_concurrency.clamp(1, 16) as usize;
    let base = map_url(ASSET_BASE, mirror.as_deref());
    let task_id = "launch-assets".to_string();
    let done_bytes = Arc::new(AtomicU64::new(0));
    let done_files = Arc::new(AtomicU64::new(0));
    let failed: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));

    let downloads = stream::iter(missing.into_iter()).map(|obj| {
        let app = app.clone();
        let objects_dir = objects_dir.clone();
        let base = base.clone();
        let token = token.clone();
        let done_bytes = done_bytes.clone();
        let done_files = done_files.clone();
        let failed = failed.clone();
        let task_id = task_id.clone();

        async move {
            let hash = obj.hash;
            let size = obj.size;
            let dest = objects_dir.join(&hash[..2]).join(&hash);
            let url = format!("{}/{}/{}", base, &hash[..2], hash);

            match download::fetch_to_quiet(&app, &url, &dest, Some(&hash), &token).await {
                Ok(_) => {
                    let bytes = done_bytes.fetch_add(size, Ordering::Relaxed) + size;
                    let files = done_files.fetch_add(1, Ordering::Relaxed) + 1;
                    // 每 10 个文件刷新一次整体进度（字节优先；索引缺 size 时退回按文件数）
                    if files % 10 == 0 || files == total_files {
                        let (downloaded, total) = if total_bytes > 0 {
                            (bytes, total_bytes)
                        } else {
                            (files, total_files)
                        };
                        download::report_progress(DownloadProgress {
                            task_id: task_id.clone(),
                            name: "游戏资源".into(),
                            downloaded,
                            total,
                            speed_bps: 0,
                            state: "running".into(),
                            error: None,
                        });
                    }
                    Ok(())
                }
                Err(LauncherError::Cancelled) => Err(LauncherError::Cancelled),
                Err(e) => {
                    // 和 LiquidLauncher 一样：单个资源失败不拖垮整次启动
                    emit_log(&app, &format!("资源 {hash} 下载失败（下次启动重试）：{e}"));
                    failed
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .push(hash);
                    Ok(())
                }
            }
        }
    });

    if let Err(e) = downloads
        .buffer_unordered(concurrency)
        .try_collect::<Vec<()>>()
        .await
    {
        download::report_progress(DownloadProgress {
            task_id: task_id.clone(),
            name: "游戏资源".into(),
            downloaded: 0,
            total: 0,
            speed_bps: 0,
            state: "cancelled".into(),
            error: None,
        });
        return Err(e);
    }

    // 收尾：进度条推满并结束
    let (final_downloaded, final_total) = if total_bytes > 0 {
        (total_bytes, total_bytes)
    } else {
        (total_files, total_files)
    };
    download::report_progress(DownloadProgress {
        task_id,
        name: "游戏资源".into(),
        downloaded: final_downloaded,
        total: final_total,
        speed_bps: 0,
        state: "done".into(),
        error: None,
    });

    let failed_count = failed.lock().unwrap_or_else(|e| e.into_inner()).len() as u64;
    if failed_count == 0 {
        emit_log(app, &format!("游戏资源下载完成（{total_files} 个）"));
    } else {
        emit_log(
            app,
            &format!("有 {failed_count}/{total_files} 个资源下载失败，再次启动会自动补齐"),
        );
        if failed_count == total_files {
            return Err(LauncherError::Message(format!(
                "全部 {total_files} 个资源下载失败，请检查网络或下载源设置"
            )));
        }
    }

    Ok(index.id.clone())
}

pub async fn ensure_client_jar(
    app: &AppHandle,
    profile: &VersionProfile,
    token: &CancellationToken,
) -> Result<PathBuf> {
    let client = profile
        .downloads
        .as_ref()
        .and_then(|d| d.client.as_ref())
        .ok_or_else(|| LauncherError::Message("版本 JSON 缺少 client 下载信息".into()))?;

    let dest = paths::get()
        .versions_dir()
        .join(&profile.id)
        .join(format!("{}.jar", profile.id));
    if dest.exists() {
        return Ok(dest);
    }
    let mirror = app
        .state::<crate::state::AppState>()
        .settings
        .lock()
        .unwrap()
        .mirror
        .clone();
    download::fetch_to(
        app,
        "launch-client",
        "Minecraft 客户端本体",
        &map_url(&client.url, mirror.as_deref()),
        &dest,
        Some(&client.sha1),
        token,
    )
    .await?;
    Ok(dest)
}

/// 同步 zip 解压（只解 .dll/.so 等 natives 需要的文件）
pub fn extract_zip_to(zip_path: &Path, dest_dir: &Path) -> Result<()> {
    let file = std::fs::File::open(zip_path)?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| LauncherError::Message(format!("打开 zip 失败 {}: {e}", zip_path.display())))?;
    std::fs::create_dir_all(dest_dir)?;

    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| {
            LauncherError::Message(format!("zip 条目读取失败 (#{i}): {e}"))
        })?;
        let Some(name) = entry.enclosed_name().map(|p| p.to_path_buf()) else {
            continue; // 跳过不安全路径
        };
        if name.to_string_lossy().ends_with('/') {
            continue;
        }
        let dest = dest_dir.join(&name);
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = std::fs::File::create(&dest)?;
        std::io::copy(&mut entry, &mut out)?;
    }
    Ok(())
}
