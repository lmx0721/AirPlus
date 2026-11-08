/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * Modrinth 模组搜索与安装（api.modrinth.com v2），按 MC 1.8.9 + forge 过滤。
 */
use crate::download;
use crate::error::{LauncherError, Result};
use crate::paths;
use serde::{Deserialize, Serialize};
use tauri::AppHandle;

const API: &str = "https://api.modrinth.com/v2";
const MC_VERSION: &str = "1.8.9";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModrinthHit {
    pub project_id: String,
    pub title: String,
    pub slug: String,
    pub description: String,
    pub downloads: u64,
    pub icon_url: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ModrinthVersion {
    pub id: String,
    /// 所属项目 id（写下载清单用；前端会原样回传）
    #[serde(default)]
    pub project_id: Option<String>,
    pub version_number: String,
    pub name: String,
    pub date_published: String,
    /// primary 文件名
    pub file_name: String,
    /// primary 文件下载地址
    pub file_url: String,
    pub file_size: u64,
}

#[derive(Debug, Deserialize)]
struct SearchResponse {
    hits: Vec<SearchHit>,
}

#[derive(Debug, Deserialize)]
struct SearchHit {
    project_id: String,
    title: String,
    slug: String,
    description: String,
    downloads: u64,
    icon_url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct VersionFile {
    url: String,
    filename: String,
    primary: bool,
    size: u64,
}

#[derive(Debug, Deserialize)]
struct ProjectVersion {
    id: String,
    #[serde(default)]
    project_id: Option<String>,
    #[serde(rename = "version_number")]
    version_number: String,
    name: String,
    #[serde(rename = "date_published")]
    date_published: String,
    files: Vec<VersionFile>,
}

fn facets() -> String {
    format!(
        r#"[["versions:{MC_VERSION}"],["categories:forge"]]"#
    )
}

#[tauri::command]
pub async fn modrinth_search(query: String) -> Result<Vec<ModrinthHit>> {
    let resp = crate::net::get()
        .get(format!("{API}/search"))
        .query(&[
            ("query", query.as_str()),
            ("facets", facets().as_str()),
            ("index", "downloads"),
            ("limit", "20"),
        ])
        .send()
        .await
        .map_err(|e| LauncherError::Message(format!("Modrinth 搜索失败: {e}")))?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("Modrinth 搜索请求失败: {e}")))?;
    let parsed: SearchResponse = resp
        .json()
        .await
        .map_err(|e| LauncherError::Message(format!("Modrinth 搜索解析失败: {e}")))?;
    Ok(parsed
        .hits
        .into_iter()
        .map(|h| ModrinthHit {
            project_id: h.project_id,
            title: h.title,
            slug: h.slug,
            description: h.description,
            downloads: h.downloads,
            icon_url: h.icon_url,
        })
        .collect())
}

#[tauri::command]
pub async fn modrinth_versions(project_id: String) -> Result<Vec<ModrinthVersion>> {
    let resp = crate::net::get()
        .get(format!("{API}/project/{project_id}/version"))
        .query(&[
            ("game_versions", format!(r#"["{MC_VERSION}"]"#).as_str()),
            ("loaders", r#"["forge"]"#),
        ])
        .send()
        .await
        .map_err(|e| LauncherError::Message(format!("Modrinth 版本获取失败: {e}")))?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("Modrinth 版本请求失败: {e}")))?;
    let versions: Vec<ProjectVersion> = resp
        .json()
        .await
        .map_err(|e| LauncherError::Message(format!("Modrinth 版本解析失败: {e}")))?;
    Ok(versions
        .into_iter()
        .filter_map(|v| {
            let file = v
                .files
                .iter()
                .find(|f| f.primary)
                .or_else(|| v.files.first())?;
            Some(ModrinthVersion {
                id: v.id,
                project_id: v.project_id,
                version_number: v.version_number,
                name: v.name,
                date_published: v.date_published,
                file_name: file.filename.clone(),
                file_url: file.url.clone(),
                file_size: file.size,
            })
        })
        .take(15)
        .collect())
}

/// 下载模组文件到实例 mods 目录（后台任务，复用下载管线）
#[tauri::command]
pub async fn modrinth_install(app: tauri::AppHandle, version: ModrinthVersion) -> String {
    let dest = paths::get().mods_dir.join(&version.file_name);
    let origin = crate::manifest::Origin::modrinth(
        version.project_id.clone(),
        Some(version.id.clone()),
        Some(version.version_number.clone()),
    );
    download::start_task(
        &app,
        &format!("{} {}", version.name, version.version_number),
        &version.file_url,
        dest,
        None,
        Some(origin),
    )
}

// ---------- 已安装模组管理 ----------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModFile {
    pub file_name: String,
    pub size: u64,
}

/// 列 mods 目录。目录扫描 + metadata 全是同步 IO，下沉到阻塞线程池。
#[tauri::command]
pub async fn mods_list() -> Result<Vec<ModFile>> {
    tauri::async_runtime::spawn_blocking(|| {
        let mods_dir = paths::get().mods_dir.clone();
        let mut list = Vec::new();
        for entry in std::fs::read_dir(&mods_dir)?.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.ends_with(".jar") {
                continue;
            }
            list.push(ModFile {
                file_name: name,
                size: entry.metadata().map(|m| m.len()).unwrap_or(0),
            });
        }
        list.sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase()));
        Ok(list)
    })
    .await
    .map_err(|e| LauncherError::Message(format!("扫描 mods 目录失败: {e}")))?
}

#[tauri::command]
pub async fn mod_delete(file_name: String) -> Result<()> {
    tauri::async_runtime::spawn_blocking(move || {
        // 只允许删除 mods 目录内的 .jar，防路径穿越
        let name = std::path::Path::new(&file_name);
        if name.file_name().map(|n| n.to_string_lossy()) != Some(file_name.as_str().into())
            || !file_name.ends_with(".jar")
        {
            return Err(LauncherError::Message("非法文件名".into()));
        }
        let path = paths::get().mods_dir.join(&file_name);
        if path.exists() {
            std::fs::remove_file(path)?;
        }
        // 同步清理下载清单里的记录
        crate::manifest::forget_mod(&file_name);
        Ok(())
    })
    .await
    .map_err(|e| LauncherError::Message(format!("删除模组失败: {e}")))?
}
