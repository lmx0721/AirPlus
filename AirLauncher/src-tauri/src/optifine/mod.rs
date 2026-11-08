/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * OptiFine：downloads 页解析版本列表 -> adloadx 二跳取 downloadx token -> 直链下载。
 * 已验证链路：downloads 页 adloadx?f= 镜像链接；adloadx 页 downloadx?f=..&x=<hex> 真实下载。
 * 官网结构变化时 fallback 为前端「导入本地 jar」（mod_import_file）。
 */
use crate::download;
use crate::error::{LauncherError, Result};
use crate::paths;
use serde::Serialize;
use tauri::AppHandle;

const MC_VERSION: &str = "1.8.9";
const DOWNLOADS_URL: &str = "https://optifine.net/downloads";
const ADLOAD_URL: &str = "https://optifine.net/adloadx";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptifineVersion {
    /// 如 OptiFine_1.8.9_HD_U_M5（无扩展名）
    pub file_name: String,
    /// 展示名，如 HD U M5
    pub display: String,
    pub preview: bool,
}

/// 从 downloads 页解析 1.8.9 可用版本列表
#[tauri::command]
pub async fn optifine_versions() -> Result<Vec<OptifineVersion>> {
    let resp = crate::net::get()
        .get(DOWNLOADS_URL)
        .header("Referer", "https://optifine.net")
        .send()
        .await
        .map_err(|e| LauncherError::Message(format!("OptiFine 官网请求失败: {e}")))?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("OptiFine 官网返回错误: {e}")))?;
    let html = resp
        .text()
        .await
        .map_err(|e| LauncherError::Message(format!("OptiFine 页面读取失败: {e}")))?;

    // 页面行：<td class='colDownload'><a href="...(ad)loadx?f=OptiFine_1.8.9_HD_U_M5.jar&x=..">
    // preview 版文件名带 preview_ 前缀
    let re = regex::Regex::new(r#"adloadx\?f=((preview_)?OptiFine_1\.8\.9_[A-Za-z0-9_]+)\.jar"#)
        .map_err(|e| LauncherError::Message(format!("内部错误（正则构建失败）: {e}")))?;

    let mut versions: Vec<OptifineVersion> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for cap in re.captures_iter(&html) {
        let preview = cap.get(2).is_some();
        let file_name = cap[1].to_string();
        if !seen.insert(file_name.clone()) {
            continue;
        }
        let display = file_name
            .strip_prefix("preview_")
            .unwrap_or(&file_name)
            .strip_prefix(&format!("OptiFine_{MC_VERSION}_"))
            .unwrap_or(&file_name)
            .replace('_', " ");
        versions.push(OptifineVersion {
            file_name,
            display,
            preview,
        });
    }

    if versions.is_empty() {
        return Err(LauncherError::Message(
            "未能从 OptiFine 官网解析出版本列表（页面结构可能已变化），请使用手动导入".into(),
        ));
    }
    Ok(versions)
}

/// 二跳解析 downloadx 真实链接并启动后台下载到 mods 目录
#[tauri::command]
pub async fn optifine_install(app: tauri::AppHandle, file_name: String) -> Result<String> {
    if !file_name.starts_with(&format!("OptiFine_{MC_VERSION}_")) || file_name.contains("..") {
        return Err(LauncherError::Message("非法的 OptiFine 文件名".into()));
    }

    // 1. adloadx 页面拿 downloadx?..&x=<hex>
    let resp = crate::net::get()
        .get(ADLOAD_URL)
        .query(&[("f", format!("{file_name}.jar").as_str())])
        .header("Referer", DOWNLOADS_URL)
        .send()
        .await
        .map_err(|e| LauncherError::Message(format!("OptiFine 中转页请求失败: {e}")))?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("OptiFine 中转页返回错误: {e}")))?;
    let html = resp
        .text()
        .await
        .map_err(|e| LauncherError::Message(format!("OptiFine 中转页读取失败: {e}")))?;

    let re = regex::Regex::new(r#"downloadx\?f=[A-Za-z0-9_.]+&x=[a-f0-9]+"#)
        .map_err(|e| LauncherError::Message(format!("内部错误（正则构建失败）: {e}")))?;
    let path = re
        .find(&html)
        .map(|m| m.as_str())
        .ok_or_else(|| {
            LauncherError::Message(
                "OptiFine 下载链接解析失败（token 可能已变更），请稍后重试或使用手动导入".into(),
            )
        })?
        .to_string();

    // 2. 后台下载
    let dest = paths::get().mods_dir.join(format!("{file_name}.jar"));
    let url = format!("https://optifine.net/{path}");
    Ok(download::start_task(
        &app,
        &file_name,
        &url,
        dest,
        None,
        Some(crate::manifest::Origin::optifine(Some(file_name.clone()))),
    ))
}

/// 手动导入本地 OptiFine/模组 jar（dialog 选路径，后台线程复制进 mods 目录）
#[tauri::command]
pub async fn mod_import_file(source_path: String) -> Result<String> {
    let file_name = std::path::Path::new(&source_path)
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| LauncherError::Message("非法的源文件路径".into()))?
        .to_string();
    if !file_name.ends_with(".jar") || file_name.contains("..") {
        return Err(LauncherError::Message("仅支持 .jar 文件".into()));
    }
    tauri::async_runtime::spawn_blocking(move || -> Result<String> {
        let src = std::path::Path::new(&source_path);
        let meta = std::fs::metadata(src)
            .map_err(|e| LauncherError::Message(format!("无法读取源文件: {e}")))?;
        if meta.len() == 0 {
            return Err(LauncherError::Message("文件内容为空".into()));
        }
        let dest = paths::get().mods_dir.join(&file_name);
        std::fs::copy(src, &dest)?;
        // 手动导入的文件也要进下载清单，否则「装过哪些模组」会缺一块
        match crate::download::sha1_file_sync(&dest) {
            Ok(sha1) => crate::manifest::record_download(
                &crate::manifest::Origin::manual(),
                &file_name,
                &dest.to_string_lossy(),
                meta.len(),
                sha1,
            ),
            Err(e) => eprintln!("计算导入文件 sha1 失败: {e}"),
        }
        Ok(dest.to_string_lossy().to_string())
    })
    .await
    .map_err(|e| LauncherError::Message(format!("导入任务失败: {e}")))?
}
