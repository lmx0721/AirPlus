/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
mod auth;
mod client;
mod config;
mod download;
mod error;
mod events;
mod forge;
mod github;
mod java;
mod launch;
mod manifest;
mod modrinth;
mod mojang;
mod net;
mod optifine;
mod paths;
mod state;

use config::Settings;
use error::Result;
use state::AppState;
use tauri::Manager;

// ---------- 基础 ----------
//
// 注意：Tauri 2 里不带 `async` 的 command 会在**主线程**执行，
// 一旦里面出现阻塞（等锁、IO、进程创建）就会把窗口拖成「未响应」。
// 所以这里的命令一律 async；带 `State<'_, T>` 的必须返回 Result 才能过借用检查。

#[tauri::command]
async fn paths_get() -> &'static paths::AppPaths {
    paths::get()
}

/// 用系统默认程序打开数据目录内的路径。
///
/// 前端直接调 opener 的 `openPath` 需要 `opener:allow-open-path` 权限，
/// 权限没打进包时会直接报 `Not allowed to open path …`（踩过）。
/// 这里走 Rust 侧的 opener，只放行数据目录内的路径，顺带避免变成任意路径打开器。
#[tauri::command]
async fn open_path(app: tauri::AppHandle, path: String) -> Result<()> {
    use tauri_plugin_opener::OpenerExt;
    let root = paths::get().root.to_string_lossy().to_string();
    if !path.starts_with(&root) {
        return Err(error::LauncherError::Message(
            "只允许打开启动器数据目录内的路径".into(),
        ));
    }
    app.opener()
        .open_path(path, None::<&str>)
        .map_err(|e| error::LauncherError::Message(format!("打开失败: {e}")))
}

#[tauri::command]
async fn settings_get(state: tauri::State<'_, AppState>) -> Result<Settings> {
    Ok(state.settings_snapshot())
}

/// 保存设置。落盘是同步 IO，下沉到阻塞线程池，避免卡住主线程。
#[tauri::command]
async fn settings_set(state: tauri::State<'_, AppState>, settings: Settings) -> Result<()> {
    {
        let mut current = state.settings.lock().unwrap_or_else(|e| e.into_inner());
        *current = settings.clone();
    }
    tauri::async_runtime::spawn_blocking(move || settings.save(&paths::get().settings_file))
        .await
        .map_err(|e| error::LauncherError::Message(format!("保存设置失败: {e}")))?
}

// ---------- 下载 / GitHub ----------

#[tauri::command]
async fn github_list_releases(state: tauri::State<'_, AppState>) -> Result<Vec<github::GhRelease>> {
    let pat = state.settings_snapshot().github_pat;
    github::list_releases(pat).await
}

#[tauri::command]
async fn github_download_asset(
    app: tauri::AppHandle,
    asset: github::GhAsset,
    tag: Option<String>,
) -> Result<String> {
    github::download_asset(&app, &asset, tag)
}

#[tauri::command]
async fn download_cancel(app: tauri::AppHandle, task_id: String) {
    download::cancel_task(&app, &task_id);
}

// ---------- 启动游戏 ----------

#[tauri::command]
async fn launch_start(app: tauri::AppHandle, options: launch::LaunchOptions) -> Result<()> {
    launch::start(&app, options);
    Ok(())
}

#[tauri::command]
async fn launch_cancel(app: tauri::AppHandle) {
    launch::cancel(&app);
}

#[tauri::command]
async fn launch_status(app: tauri::AppHandle) -> Result<launch::LaunchStatus> {
    Ok(launch::status(&app))
}

/// 应用窗口背景效果。
///
/// Win11 上 `Effect::Acrylic` 走 DWMWA_SYSTEMBACKDROP_TYPE（GPU 合成，拖动流畅）；
/// 真正会拖慢拖动的是 Win10 的旧 accent 实现，那种情况用户可以在设置里切「云母」或「不透明」。
fn apply_window_effect(
    win: &tauri::WebviewWindow,
    effect: &str,
) -> std::result::Result<(), tauri::Error> {
    use tauri::window::{Effect, EffectsBuilder};
    match effect {
        "acrylic" => win.set_effects(EffectsBuilder::new().effect(Effect::Acrylic).build()),
        "mica" => win.set_effects(EffectsBuilder::new().effect(Effect::Mica).build()),
        _ => win.set_effects(None::<tauri::utils::config::WindowEffectsConfig>),
    }
}

#[tauri::command]
async fn window_set_effect(window: tauri::WebviewWindow, effect: String) -> Result<()> {
    apply_window_effect(&window, &effect)
        .map_err(|e| error::LauncherError::Message(format!("窗口效果设置失败: {e}")))
}

/// 主线程看门狗：每 500ms 向主线程投递一个空任务，2s 内没被执行就说明
/// 消息循环卡住（即 Windows 的「未响应」），把时间戳与业务上下文追加到
/// `<数据目录>/hang.log`。仅诊断用途，不影响正常运行。
fn spawn_main_thread_watchdog(app: &tauri::AppHandle) {
    use std::sync::mpsc;
    use std::time::Duration;

    let handle = app.clone();
    std::thread::spawn(move || {
        let (tx, rx) = mpsc::channel::<()>();
        loop {
            std::thread::sleep(Duration::from_millis(500));

            let tx_ping = tx.clone();
            if handle
                .run_on_main_thread(move || {
                    let _ = tx_ping.send(());
                })
                .is_err()
            {
                // 应用正在退出
                return;
            }

            if rx.recv_timeout(Duration::from_secs(2)).is_err() {
                let now = chrono_like_now();
                // 这里绝对不能用阻塞 lock()：主线程一旦卡死，很可能正持着这把锁，
                // 看门狗会被同一个故障点拖住，永远写不出诊断日志（本项目踩过）。
                // 取不到锁本身就说明有人长期持有 → 更可疑，记为 true。
                let launching = handle
                    .state::<AppState>()
                    .launch_pid
                    .try_lock()
                    .map(|p| p.is_some())
                    .unwrap_or(true);
                let line = format!("[{now}] 主线程 2s 未响应 (launching={launching})\n");
                eprintln!("{line}");
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(paths::get().root.join("hang.log"))
                {
                    use std::io::Write;
                    let _ = f.write_all(line.as_bytes());
                }
                // 冷却，避免刷屏
                std::thread::sleep(Duration::from_secs(10));
                while rx.try_recv().is_ok() {}
            }
        }
    });
}

/// 无第三方时间库的本地时间戳（仅用于日志，精度秒级足够）
fn chrono_like_now() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    secs.to_string()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let p = paths::init(&handle);

            let settings = Settings::load(&p.settings_file);
            let initial_effect = settings.window_effect.clone();
            app.manage(AppState::new(settings));
            // 配置里的 windowEffects 已生效，这里再按用户设置兜一次（老配置可能存的是别的值）
            if let Some(win) = app.get_webview_window("main") {
                let _ = apply_window_effect(&win, &initial_effect);
            }
            spawn_main_thread_watchdog(&handle);
            download::spawn_progress_flusher(handle.clone());
            // 启动时把下载清单与 mods 目录对齐（present / active 标记）
            tauri::async_runtime::spawn_blocking(manifest::sync);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            paths_get,
            open_path,
            settings_get,
            settings_set,
            window_set_effect,
            github_list_releases,
            github_download_asset,
            client::client_activate,
            client::client_status,
            download_cancel,
            launch_start,
            launch_cancel,
            launch_status,
            auth::auth_login,
            auth::auth_cancel,
            auth::accounts_list,
            auth::account_remove,
            auth::account_refresh,
            modrinth::modrinth_search,
            modrinth::modrinth_versions,
            modrinth::modrinth_install,
            modrinth::mods_list,
            modrinth::mod_delete,
            manifest::manifest_get,
            optifine::optifine_versions,
            optifine::optifine_install,
            optifine::mod_import_file,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
