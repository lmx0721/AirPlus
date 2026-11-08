/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * Forge 1.8.9 安装：不运行官方 installer，直接解析其 jar 包内容。
 * - install_profile.json 的 versionInfo 即继承版 version JSON（FMLTweaker + launchwrapper）
 * - universal jar 位于 installer 根目录（install.filePath），写入 libraries 的 maven 路径
 * - binpatches.pack.lzma 内嵌于 universal jar，由 FML 运行时自动应用，无需预打补丁
 */
use crate::download;
use crate::error::{LauncherError, Result};
use crate::events;
use crate::mojang;
use crate::paths;
use serde_json::Value;
use std::io::Read;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter};
use tokio_util::sync::CancellationToken;

/// 1.8.9 对应的 Forge 版本（与 AirPlus 依赖的 11.15.1.2318 一致）
pub const FORGE_VERSION: &str = "1.8.9-11.15.1.2318-1.8.9";
const MC_VERSION: &str = "1.8.9";

pub fn installer_url() -> String {
    format!(
        "https://maven.minecraftforge.net/net/minecraftforge/forge/{v}/forge-{v}-installer.jar",
        v = FORGE_VERSION
    )
}

/// 安装后的版本 id（与 installer install.target 一致）
pub fn version_id() -> String {
    format!("{MC_VERSION}-forge{FORGE_VERSION}")
}

pub fn version_json_path() -> PathBuf {
    paths::get()
        .versions_dir()
        .join(version_id())
        .join(format!("{}.json", version_id()))
}

/// 是否已安装（version json + universal jar 齐备）
pub fn is_installed() -> bool {
    let universal = universal_jar_path();
    version_json_path().exists() && universal.exists()
}

fn universal_jar_path() -> PathBuf {
    universal_jar_path_in(&paths::get().libraries_dir())
}

fn universal_jar_path_in(libraries_dir: &std::path::Path) -> PathBuf {
    // install.path = net.minecraftforge:forge:<ver> -> maven 路径
    let name = format!("net.minecraftforge:forge:{FORGE_VERSION}");
    libraries_dir.join(mojang::maven_path(&name, "jar"))
}

fn emit_stage(app: &AppHandle, stage: &str) {
    let _ = app.emit(events::FORGE_STAGE, stage.to_string());
}

/// 确保 Forge 已安装（可取消；重复调用幂等）
pub async fn ensure_installed(app: &AppHandle, token: &CancellationToken) -> Result<()> {
    if is_installed() {
        emit_stage(app, "ready");
        return Ok(());
    }

    emit_stage(app, "downloading");
    let installer = download::cache_path(&format!("forge-{FORGE_VERSION}-installer.jar"));
    if !installer.exists() {
        download::fetch_to(
            app,
            "forge-install",
            "Forge 安装器",
            &mojang::map_url(&installer_url(), None), // Forge maven 不走 Mojang 镜像映射
            &installer,
            None,
            token,
        )
        .await?;
    }

    emit_stage(app, "extracting");
    let installer_c = installer.clone();
    let root = paths::get().instance_dir.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<()> {
        extract_installer_to(&installer_c, &root)?;
        Ok(())
    })
    .await
    .map_err(|e| LauncherError::Message(format!("Forge 解包任务失败: {e}")))??;

    emit_stage(app, "ready");
    Ok(())
}

/// 同步解包 installer：写 version json + universal jar（root 为实例根目录）
fn extract_installer_to(installer: &std::path::Path, root: &std::path::Path) -> Result<()> {
    let versions_dir = root.join("versions");
    let libraries_dir = root.join("libraries");
    let file = std::fs::File::open(installer)?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| LauncherError::Message(format!("Forge installer 读取失败: {e}")))?;

    // 1. install_profile.json -> versionInfo 写为继承版版本 JSON
    let mut profile_raw = String::new();
    archive
        .by_name("install_profile.json")
        .map_err(|e| LauncherError::Message(format!("installer 缺少 install_profile.json: {e}")))?
        .read_to_string(&mut profile_raw)?;
    let profile: Value = serde_json::from_str(&profile_raw)
        .map_err(|e| LauncherError::Message(format!("install_profile.json 解析失败: {e}")))?;
    let version_info = profile
        .get("versionInfo")
        .ok_or_else(|| LauncherError::Message("install_profile.json 缺少 versionInfo".into()))?;

    let json_path = versions_dir
        .join(version_id())
        .join(format!("{}.json", version_id()));
    std::fs::create_dir_all(
        json_path
            .parent()
            .ok_or_else(|| LauncherError::Message("version json 路径异常".into()))?,
    )?;
    std::fs::write(&json_path, serde_json::to_vec_pretty(version_info)?)?;

    // 2. universal jar -> libraries maven 路径
    let file_name = profile
        .pointer("/install/filePath")
        .and_then(|v| v.as_str())
        .ok_or_else(|| LauncherError::Message("install_profile.json 缺少 install.filePath".into()))?
        .to_string();
    let mut universal = Vec::new();
    archive
        .by_name(&file_name)
        .map_err(|e| LauncherError::Message(format!("installer 缺少 {file_name}: {e}")))?
        .read_to_end(&mut universal)?;

    let dest = universal_jar_path_in(&libraries_dir);
    std::fs::create_dir_all(
        dest.parent()
            .ok_or_else(|| LauncherError::Message("universal jar 路径异常".into()))?,
    )?;
    std::fs::write(&dest, universal)?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// AIRLAUNCHER_TEST_FORGE_INSTALLER 指向真实的 forge installer jar 时运行
    #[test]
    fn extract_real_installer() {
        let Ok(path) = std::env::var("AIRLAUNCHER_TEST_FORGE_INSTALLER") else {
            eprintln!("跳过：未设置 AIRLAUNCHER_TEST_FORGE_INSTALLER");
            return;
        };
        let tmp = std::env::temp_dir().join(format!("airlauncher-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);

        extract_installer_to(std::path::Path::new(&path), &tmp).unwrap();

        let json = tmp
            .join("versions")
            .join(version_id())
            .join(format!("{}.json", version_id()));
        assert!(json.exists(), "version json 未写出: {}", json.display());
        let parsed: Value = serde_json::from_str(&std::fs::read_to_string(&json).unwrap()).unwrap();
        assert_eq!(
            parsed["inheritsFrom"].as_str().unwrap(),
            MC_VERSION,
            "inheritsFrom 应指向 vanilla 版本"
        );
        assert_eq!(
            parsed["mainClass"].as_str().unwrap(),
            "net.minecraft.launchwrapper.Launch"
        );

        let universal = universal_jar_path_in(&tmp.join("libraries"));
        assert!(universal.exists(), "universal jar 未写出: {}", universal.display());
        assert!(
            std::fs::metadata(&universal).unwrap().len() > 3_000_000,
            "universal jar 尺寸异常"
        );

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
