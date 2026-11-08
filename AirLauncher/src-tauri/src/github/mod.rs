/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
use crate::download;
use crate::error::Result;
use crate::net;
use crate::paths;
use serde::{Deserialize, Serialize};

const RELEASES_API: &str = "https://api.github.com/repos/lmx0721/AirPlus/releases";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GhAsset {
    pub id: u64,
    pub name: String,
    pub size: u64,
    pub browser_download_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GhRelease {
    pub tag_name: String,
    pub name: Option<String>,
    pub body: Option<String>,
    pub published_at: Option<String>,
    pub prerelease: bool,
    pub assets: Vec<GhAsset>,
}

pub async fn list_releases(pat: Option<String>) -> Result<Vec<GhRelease>> {
    let mut req = net::get()
        .get(RELEASES_API)
        .query(&[("per_page", "20")])
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28");
    if let Some(token) = pat.filter(|t| !t.trim().is_empty()) {
        req = req.bearer_auth(token.trim());
    }
    let resp = req.send().await?.error_for_status()?;
    Ok(resp.json().await?)
}

/// 把 release asset（客户端 jar）直接下载到 mods 目录。
/// 下载完成后由前端调用 `client_activate` 清理旧版本，使其成为唯一生效的客户端。
///
/// `tag` 是 release 标签（如 b4），会写进下载清单，用来回答「下过哪些版本」。
pub fn download_asset(
    app: &tauri::AppHandle,
    asset: &GhAsset,
    tag: Option<String>,
) -> Result<String> {
    let dest = paths::get().mods_dir.join(&asset.name);
    Ok(download::start_task(
        app,
        &asset.name,
        &asset.browser_download_url,
        dest,
        None,
        Some(crate::manifest::Origin::client(tag)),
    ))
}
