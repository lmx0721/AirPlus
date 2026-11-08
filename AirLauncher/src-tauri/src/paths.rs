/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 所有路径均在运行时从 app_local_data_dir 派生，禁止硬编码绝对路径。
 */
use serde::Serialize;
use std::path::PathBuf;
use std::sync::OnceLock;
use tauri::Manager;

static PATHS: OnceLock<AppPaths> = OnceLock::new();

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppPaths {
    /// 数据根目录（%LOCALAPPDATA%/<identifier>）
    pub root: PathBuf,
    /// 实例总目录
    pub instances_dir: PathBuf,
    /// 唯一实例根（游戏工作目录）
    pub instance_dir: PathBuf,
    /// 实例 mods 目录
    pub mods_dir: PathBuf,
    /// JRE 安装目录
    pub jre_dir: PathBuf,
    /// 下载缓存目录（installer、临时文件）
    pub cache_dir: PathBuf,
    /// settings.json
    pub settings_file: PathBuf,
    /// accounts.json
    pub accounts_file: PathBuf,
    /// launcher-manifest.json（下载清单：客户端版本 + 模组 + sha1）
    pub manifest_file: PathBuf,
}

impl AppPaths {
    fn build(app: &tauri::AppHandle) -> AppPaths {
        let root = app
            .path()
            .app_local_data_dir()
            .expect("无法确定应用数据目录");

        let instances_dir = root.join("instances");
        let instance_dir = instances_dir.join("airplus-b4");
        let mods_dir = instance_dir.join("mods");
        let jre_dir = root.join("jre");
        let cache_dir = root.join("cache");

        for dir in [
            &root,
            &instances_dir,
            &instance_dir,
            &mods_dir,
            &jre_dir,
            &cache_dir,
        ] {
            std::fs::create_dir_all(dir).expect("无法创建启动器目录结构");
        }

        AppPaths {
            settings_file: root.join("settings.json"),
            accounts_file: root.join("accounts.json"),
            manifest_file: instance_dir.join(crate::manifest::FILE_NAME),
            root,
            instances_dir,
            instance_dir,
            mods_dir,
            jre_dir,
            cache_dir,
        }
    }

    pub fn jre_java_exe(&self) -> PathBuf {
        self.jre_dir.join("bin").join("java.exe")
    }

    /// 实例内的游戏子目录（versions/libraries/assets/natives）
    pub fn versions_dir(&self) -> PathBuf {
        self.instance_dir.join("versions")
    }

    pub fn libraries_dir(&self) -> PathBuf {
        self.instance_dir.join("libraries")
    }

    pub fn assets_dir(&self) -> PathBuf {
        self.instance_dir.join("assets")
    }

    pub fn natives_dir(&self, version: &str) -> PathBuf {
        self.instance_dir.join("natives").join(version)
    }
}

/// 在 Builder.setup 中调用一次；路径不可用时直接 panic（此时无法运行）
pub fn init(app: &tauri::AppHandle) -> &'static AppPaths {
    PATHS.get_or_init(|| AppPaths::build(app))
}

pub fn get() -> &'static AppPaths {
    PATHS.get().expect("AppPaths 尚未初始化")
}
