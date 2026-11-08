/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * Java 8 运行时：Adoptium Temurin 8 JRE (windows x64) 自动下载 + 解压。
 * Forge 1.8.9 安装与游戏运行均使用该 JRE。
 */
use crate::download;
use crate::error::{LauncherError, Result};
use crate::events;
use crate::paths;
use serde::Deserialize;
use tauri::{AppHandle, Emitter};
use tokio_util::sync::CancellationToken;

const ADOPTIUM_API: &str = "https://api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&image_type=jre&os=windows";

#[derive(Deserialize)]
struct AdoptiumAsset {
    binary: AdoptiumBinary,
}

#[derive(Deserialize)]
struct AdoptiumBinary {
    package: AdoptiumPackage,
}

#[derive(Deserialize)]
struct AdoptiumPackage {
    link: String,
    name: String,
}

fn java_exe() -> std::path::PathBuf {
    paths::get().jre_java_exe()
}

pub fn is_installed() -> bool {
    java_exe().exists()
}

fn emit_stage(app: &AppHandle, stage: &str) {
    let _ = app.emit(events::LAUNCH_LOG, format!("[java] {stage}"));
}

/// 确保本机 JRE 8 可用（幂等，可取消）
pub async fn ensure_installed(app: &AppHandle, token: &CancellationToken) -> Result<()> {
    if is_installed() {
        return Ok(());
    }

    emit_stage(app, "查询 Adoptium Temurin 8 JRE…");
    let resp = token
        .run_until_cancelled(crate::net::get().get(ADOPTIUM_API).send())
        .await
        .ok_or(LauncherError::Cancelled)?
        .map_err(|e| LauncherError::Message(format!("Adoptium API 请求失败: {e}")))?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("Adoptium API 返回错误: {e}")))?;
    let assets: Vec<AdoptiumAsset> = resp
        .json()
        .await
        .map_err(|e| LauncherError::Message(format!("Adoptium API 解析失败: {e}")))?;
    let asset = assets
        .first()
        .ok_or_else(|| LauncherError::Message("Adoptium 未返回可用 JRE 8".into()))?;

    let zip_path = download::cache_path(&asset.binary.package.name);
    if !zip_path.exists() {
        emit_stage(app, "下载 Temurin 8 JRE…");
        download::fetch_to(
            app,
            "jre-download",
            "Temurin 8 JRE",
            &asset.binary.package.link,
            &zip_path,
            None,
            token,
        )
        .await?;
    }

    emit_stage(app, "解压 JRE…");
    // 解压时去掉 zip 内层目录（如 jdk8u512-b09-jre/），使 java.exe 落在 jre/bin/java.exe
    let zip_c = zip_path.clone();
    let dest = paths::get().jre_dir.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<()> {
        extract_jre(&zip_c, &dest)
    })
    .await
    .map_err(|e| LauncherError::Message(format!("JRE 解压任务失败: {e}")))??;

    if !is_installed() {
        return Err(LauncherError::Message(
            "JRE 解压完成但未找到 bin/java.exe".into(),
        ));
    }
    emit_stage(app, "JRE 就绪");
    Ok(())
}
/// 同步解压：如果 zip 只有一个内层根目录，把它拍平
fn extract_jre(zip_path: &std::path::Path, dest: &std::path::Path) -> Result<()> {
    let file = std::fs::File::open(zip_path)?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| LauncherError::Message(format!("JRE zip 读取失败: {e}")))?;

    // 判断内层目录
    let mut common_root: Option<String> = None;
    for i in 0..archive.len() {
        let name = archive
            .by_index_raw(i)
            .map_err(|e| LauncherError::Message(format!("JRE zip 条目读取失败 (#{i}): {e}")))?
            .name()
            .to_string();
        let Some(first) = name.split('/').next() else {
            continue;
        };
        if first.is_empty() || name == first {
            common_root = None;
            break;
        }
        match &common_root {
            None => common_root = Some(first.to_string()),
            Some(r) if r != first => {
                common_root = None;
                break;
            }
            _ => {}
        }
    }
    let root = common_root.unwrap_or_default();
    let root_prefix = if root.is_empty() {
        String::new()
    } else {
        format!("{root}/")
    };

    std::fs::create_dir_all(dest)?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| {
            LauncherError::Message(format!("JRE zip 条目读取失败 (#{i}): {e}"))
        })?;
        let name = entry.name().to_string();
        let Some(rel) = name.strip_prefix(&root_prefix) else {
            continue;
        };
        if rel.is_empty() || name.ends_with('/') {
            continue;
        }
        let dest_path = dest.join(rel);
        if let Some(parent) = dest_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = std::fs::File::create(&dest_path)?;
        std::io::copy(&mut entry, &mut out)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// AIRLAUNCHER_TEST_JRE_ZIP 指向 Temurin 8 JRE zip 时运行
    #[test]
    fn extract_real_jre() {
        let Ok(path) = std::env::var("AIRLAUNCHER_TEST_JRE_ZIP") else {
            eprintln!("跳过：未设置 AIRLAUNCHER_TEST_JRE_ZIP");
            return;
        };
        let tmp =
            std::env::temp_dir().join(format!("airlauncher-jre-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);

        extract_jre(std::path::Path::new(&path), &tmp).unwrap();

        assert!(tmp.join("bin/java.exe").exists(), "bin/java.exe 未拍平解出");
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
