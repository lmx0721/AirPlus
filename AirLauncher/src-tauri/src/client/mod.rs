/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 客户端本体（AirPlus mod jar）的安装与管理。
 *
 * 启动器最终拉起的是 Forge，Forge 只加载 mods/ 下的 jar；
 * 从 GitHub release 下载的客户端必须落到 mods 目录，否则进游戏就是原版界面。
 * （踩过：以前 download_asset 只把 jar 丢进 cache，启动后 mods 为空 → 原版 Forge。）
 */
use crate::download;
use crate::error::{LauncherError, Result};
use crate::events;
use crate::github;
use crate::paths;
use crate::state::AppState;
use serde::Serialize;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Emitter, Manager};
use tokio_util::sync::CancellationToken;

/// 客户端 jar 的固定前缀（AirPlus-b4.jar / AirPlus-b3.jar …）
const CLIENT_PREFIX: &str = "airplus";

fn emit_log(app: &AppHandle, msg: &str) {
    let _ = app.emit(events::LAUNCH_LOG, msg.to_string());
}

/// 文件名是否是客户端本体（大小写不敏感）
pub fn is_client_jar(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.starts_with(CLIENT_PREFIX) && lower.ends_with(".jar")
}

/// mods 目录里已有的客户端 jar
pub fn find_client_jar(mods: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(mods).ok()?;
    let mut found: Option<PathBuf> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        if is_client_jar(&entry.file_name().to_string_lossy()) {
            found = Some(path);
        }
    }
    found
}

/// 删除除 `keep` 之外的客户端 jar。
/// Forge 会把每个 jar 都当成独立 mod，多版本并存时行为不可预期（通常加载最后一个），
/// 所以切换版本时必须先清掉旧的。
pub fn remove_other_clients(mods: &Path, keep: Option<&str>) {
    let Ok(entries) = std::fs::read_dir(mods) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !is_client_jar(&name) {
            continue;
        }
        if keep.is_some_and(|k| k == name) {
            continue;
        }
        let _ = std::fs::remove_file(entry.path());
    }
}

/// 启动前确保 mods 里有客户端：有就直接用，没有就自动装最新 release。
pub async fn ensure_installed(app: &AppHandle, token: &CancellationToken) -> Result<PathBuf> {
    let mods = paths::get().mods_dir.clone();

    if let Some(existing) = find_client_jar(&mods) {
        emit_log(
            app,
            &format!(
                "已安装客户端：{}",
                existing
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default()
            ),
        );
        return Ok(existing);
    }

    emit_log(app, "mods 里还没有客户端，正在获取最新版本…");
    let pat = app.state::<AppState>().settings_snapshot().github_pat;
    let releases = github::list_releases(pat).await?;

    let release = releases
        .iter()
        .find(|r| !r.prerelease)
        .or_else(|| releases.first())
        .ok_or_else(|| LauncherError::Message("AirPlus 仓库没有任何 release".into()))?;

    let asset = release
        .assets
        .iter()
        .find(|a| {
            let lower = a.name.to_lowercase();
            lower.ends_with(".jar") && !lower.contains("sources") && !lower.contains("javadoc")
        })
        .ok_or_else(|| {
            LauncherError::Message(format!("版本 {} 里没有可用的客户端 jar", release.tag_name))
        })?;

    emit_log(
        app,
        &format!("下载客户端 {}（{}）…", asset.name, release.tag_name),
    );
    let dest = mods.join(&asset.name);
    // 走统一下载管线：有进度条、断点续传、失败重试
    let done = download::fetch_to_full(
        app,
        "launch-client-mod",
        &asset.name,
        &asset.browser_download_url,
        &dest,
        None,
        token,
    )
    .await?;

    // 记进下载清单：tag + sha1 + 时间，事后能查到「下过哪些客户端版本」
    crate::manifest::record_download(
        &crate::manifest::Origin::client(Some(release.tag_name.clone())),
        &asset.name,
        &asset.browser_download_url,
        done.size,
        done.sha1,
    );

    remove_other_clients(&mods, Some(&asset.name));
    emit_log(app, &format!("客户端安装完成：{}", asset.name));
    Ok(dest)
}

/// 把已下载的客户端设为唯一生效版本（前端在下载任务完成后调用）
#[tauri::command]
pub async fn client_activate(name: String) -> Result<()> {
    tauri::async_runtime::spawn_blocking(move || {
        let mods = paths::get().mods_dir.clone();
        if !mods.join(&name).is_file() {
            return Err(LauncherError::Message(format!(
                "客户端 {name} 还未下载完成，请稍后重试"
            )));
        }
        remove_other_clients(&mods, Some(&name));
        crate::manifest::set_active_client(&name);
        Ok(())
    })
    .await
    .map_err(|e| LauncherError::Message(format!("激活客户端失败: {e}")))?
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientStatus {
    /// mods 里是否已有任意一个 AirPlus 客户端
    pub installed: bool,
    pub file_name: Option<String>,
    /// 从文件名推断的 release 标签（AirPlus-b4.jar -> b4）
    pub tag: Option<String>,
}

/// 首页据此决定「直接启动」还是「弹窗让用户选版本下载」。
/// 只扫目录不读清单：清单可能还没同步，目录才是唯一可信来源。
#[tauri::command]
pub async fn client_status() -> Result<ClientStatus> {
    tauri::async_runtime::spawn_blocking(|| {
        let mods = paths::get().mods_dir.clone();
        let Some(path) = find_client_jar(&mods) else {
            return ClientStatus {
                installed: false,
                file_name: None,
                tag: None,
            };
        };
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        ClientStatus {
            tag: crate::manifest::tag_from_file_name(&name),
            installed: true,
            file_name: Some(name),
        }
    })
    .await
    .map_err(|e| LauncherError::Message(format!("检查客户端失败: {e}")))
}
