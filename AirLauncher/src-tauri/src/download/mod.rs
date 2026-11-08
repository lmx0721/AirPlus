/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 统一下载管线：并发限制、Range 断点续传、sha1 校验、指数退避重试、节流进度事件。
 */
use crate::error::{LauncherError, Result};
use crate::events;
use crate::manifest;
use crate::net;
use crate::paths;
use crate::state::AppState;
use futures_util::StreamExt;
use serde::Serialize;
use sha1::{Digest, Sha1};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncSeekExt, AsyncWriteExt};
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

static SEMAPHORE: OnceLock<Semaphore> = OnceLock::new();
static TASK_SEQ: AtomicU64 = AtomicU64::new(1);

const MAX_ATTEMPTS: u32 = 3;

/// 全局并发上限（兜底）。实际并发由调用方控制（设置里的 download_concurrency
/// 上限 16），这里只防止异常路径把连接数顶爆。
fn semaphore() -> &'static Semaphore {
    SEMAPHORE.get_or_init(|| Semaphore::new(16))
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadProgress {
    pub task_id: String,
    pub name: String,
    pub downloaded: u64,
    pub total: u64,
    pub speed_bps: u64,
    /// running | done | error | cancelled
    pub state: String,
    pub error: Option<String>,
}

/// 下载结果的完整信息。sha1 一律在流式写入后顺带算出，
/// 供下载清单（manifest）落盘，调用方不必再读一遍文件。
pub struct Downloaded {
    pub path: PathBuf,
    pub size: u64,
    pub sha1: String,
}

pub fn new_task_id(prefix: &str) -> String {
    format!("{}-{}", prefix, TASK_SEQ.fetch_add(1, Ordering::Relaxed))
}

/// 进度聚合器：各任务只把**最新值**写进来，由 `spawn_progress_flusher`
/// 每 100ms 汇总成一个数组 emit 一次。
///
/// 以前每条进度都单独 emit：8 并发 × 100ms = 80 events/s，
/// 每个事件都要过一遍 IPC + Svelte 失效 + TaskPanel diff，是切窗口卡顿的主因之一。
/// 现在无论多少并发，事件频率恒定 10/s，同一任务在窗口期内的多次更新自然去重。
struct ProgressHub {
    dirty: Mutex<HashMap<String, DownloadProgress>>,
}

static HUB: OnceLock<ProgressHub> = OnceLock::new();

fn hub() -> &'static ProgressHub {
    HUB.get_or_init(|| ProgressHub {
        dirty: Mutex::new(HashMap::new()),
    })
}

fn push(p: DownloadProgress) {
    hub()
        .dirty
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(p.task_id.clone(), p);
}

fn emit(_app: &AppHandle, p: &DownloadProgress) {
    push(p.clone());
}

/// 供上层模块上报「聚合进度」（例如资源包整体进度），走同一个 100ms 合批通道
pub fn report_progress(p: DownloadProgress) {
    push(p);
}

/// 启动进度汇总任务（在 lib.rs 的 setup 里调用一次）
pub fn spawn_progress_flusher(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let mut ticker = tokio::time::interval(Duration::from_millis(100));
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticker.tick().await;
            let batch: Vec<DownloadProgress> = {
                let mut map = hub().dirty.lock().unwrap_or_else(|e| e.into_inner());
                if map.is_empty() {
                    continue;
                }
                map.drain().map(|(_, v)| v).collect()
            };
            let _ = app.emit(events::DOWNLOAD_PROGRESS, batch);
        }
    });
}

/// 启动一个后台下载任务（立即返回 task_id，进度走 download://progress 事件）。
///
/// `origin` 非空时，下载成功后会往下载清单里写一条记录（客户端 / 模组 + sha1）。
pub fn start_task(
    app: &AppHandle,
    name: &str,
    url: &str,
    dest: PathBuf,
    sha1: Option<String>,
    origin: Option<manifest::Origin>,
) -> String {
    let task_id = new_task_id("dl");
    let token = CancellationToken::new();

    {
        let state = app.state::<AppState>();
        state
            .cancels
            .lock()
            .unwrap()
            .insert(task_id.clone(), token.clone());
    }

    let app = app.clone();
    let name = name.to_string();
    let url = url.to_string();
    let task_id_inner = task_id.clone();
    let file_name = dest
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();

    tauri::async_runtime::spawn(async move {
        let task_id = task_id_inner;
        let result = run_download_full(
            &app,
            Some(&task_id),
            &name,
            &url,
            &dest,
            sha1.as_deref(),
            &token,
        )
        .await;

        // 下载成功才记录，失败/取消不留痕迹
        if let (Ok(done), Some(o)) = (&result, origin.as_ref()) {
            manifest::record_download(o, &file_name, &url, done.size, done.sha1.clone());
        }

        // 终态进度由 run_download_full 统一发（内部下载与后台任务共用同一套收尾）

        app.state::<AppState>().cancels.lock().unwrap().remove(&task_id);
    });

    task_id
}

pub fn cancel_task(app: &AppHandle, task_id: &str) {
    if let Some(token) = app.state::<AppState>().cancels.lock().unwrap().get(task_id) {
        token.cancel();
    }
}

/// 完整重试循环：网络错误保留 .part 以便续传，sha1 错误清空重来。
///
/// `task_id` 为 None 时不发单文件进度事件——批量下载（如 resources 的上千个
/// 资源对象）由调用方汇总成一个整体进度上报，避免 IPC 与列表 diff 爆炸。
pub async fn run_download(
    app: &AppHandle,
    task_id: Option<&str>,
    name: &str,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<PathBuf> {
    Ok(run_download_full(app, task_id, name, url, dest, sha1_hex, token)
        .await?
        .path)
}

/// 同 `run_download`，但把大小与 sha1 一并返回（写下载清单用）。
///
/// 只要调用方给了 `task_id`，这里就保证「发过 running 进度」之后一定发一条终态。
/// 否则那些在启动流程里被 await 的内部下载（Forge / 客户端 / JRE / 库）会永远停在
/// 最后一条 running 进度上——游戏都起来了，任务面板还挂着「下载中」和取消按钮。
pub async fn run_download_full(
    app: &AppHandle,
    task_id: Option<&str>,
    name: &str,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<Downloaded> {
    let result = retry_download(app, task_id, name, url, dest, sha1_hex, token).await;
    if let Some(tid) = task_id {
        emit_terminal(tid, name, &result);
    }
    result
}

/// 终态进度：done 时把进度条推满，失败/取消则标记状态，让前端 5 秒后自动移除卡片
fn emit_terminal(task_id: &str, name: &str, result: &Result<Downloaded>) {
    let p = match result {
        Ok(d) => DownloadProgress {
            task_id: task_id.to_string(),
            name: name.to_string(),
            downloaded: d.size,
            total: d.size,
            speed_bps: 0,
            state: "done".into(),
            error: None,
        },
        Err(LauncherError::Cancelled) => DownloadProgress {
            task_id: task_id.to_string(),
            name: name.to_string(),
            downloaded: 0,
            total: 0,
            speed_bps: 0,
            state: "cancelled".into(),
            error: None,
        },
        Err(e) => DownloadProgress {
            task_id: task_id.to_string(),
            name: name.to_string(),
            downloaded: 0,
            total: 0,
            speed_bps: 0,
            state: "error".into(),
            error: Some(e.to_string()),
        },
    };
    push(p);
}

/// 带重试的下载主体：网络错误保留 .part 以便续传，sha1 错误清空重来。
async fn retry_download(
    app: &AppHandle,
    task_id: Option<&str>,
    name: &str,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<Downloaded> {
    let mut last_err: Option<LauncherError> = None;

    for attempt in 1..=MAX_ATTEMPTS {
        if token.is_cancelled() {
            return Err(LauncherError::Cancelled);
        }

        match download_once(app, task_id, name, url, dest, sha1_hex, token).await {
            Ok(p) => return Ok(p),
            Err(LauncherError::Cancelled) => return Err(LauncherError::Cancelled),
            Err(e @ LauncherError::Checksum(_)) => {
                let _ = tokio::fs::remove_file(dest).await; // 连 .part 一起不要
                let _ = tokio::fs::remove_file(dest.with_extension("part")).await;
                last_err = Some(e); // 校验失败重下也没有意义差异，但保留统一重试路径
            }
            Err(e) => {
                last_err = Some(e);
                if attempt < MAX_ATTEMPTS {
                    tokio::time::sleep(Duration::from_secs(2u64.pow(attempt))).await;
                }
            }
        }
    }

    Err(last_err
        .unwrap_or_else(|| LauncherError::Message("下载失败（未知原因）".into())))
}

async fn download_once(
    app: &AppHandle,
    task_id: Option<&str>,
    name: &str,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<Downloaded> {
    let _permit = semaphore().acquire().await;
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let part = dest.with_extension("part");
    let mut resume_from = 0u64;
    if part.exists() {
        resume_from = tokio::fs::metadata(&part).await?.len();
    }

    let mut req = net::get().get(url);
    if resume_from > 0 {
        req = req.header("Range", format!("bytes={resume_from}-"));
    }
    let resp = req.send().await?.error_for_status()?;
    let resumed = resp.status() == reqwest::StatusCode::PARTIAL_CONTENT;
    let total: u64 = resp
        .content_length()
        .map(|len| len + if resumed { resume_from } else { 0 })
        .unwrap_or(0);

    let mut file = if resumed {
        let mut f = tokio::fs::OpenOptions::new()
            .append(true)
            .open(&part)
            .await?;
        f.seek(std::io::SeekFrom::End(0)).await?;
        f
    } else {
        tokio::fs::File::create(&part).await?
    };

    let mut downloaded = if resumed { resume_from } else { 0 };
    let mut last_emit = Instant::now();
    let mut last_downloaded = downloaded;
    let mut window_start = Instant::now();
    let mut window_downloaded = 0u64;

    let mut stream = resp.bytes_stream();
    while let Some(chunk) = token
        .run_until_cancelled(stream.next())
        .await
        .ok_or(LauncherError::Cancelled)?
        .transpose()?
    {
        file.write_all(&chunk).await?;
        downloaded += chunk.len() as u64;
        window_downloaded += chunk.len() as u64;

        if last_emit.elapsed() >= Duration::from_millis(100) {
            let pct_changed = total == 0
                || (downloaded - last_downloaded) * 100 / total >= 1;
            if pct_changed {
                if let Some(tid) = task_id {
                    let speed = (window_downloaded as f64
                        / window_start.elapsed().as_secs_f64().max(0.05))
                        as u64;
                    emit(
                        app,
                        &DownloadProgress {
                            task_id: tid.to_string(),
                            name: name.to_string(),
                            downloaded,
                            total,
                            speed_bps: speed,
                            state: "running".into(),
                            error: None,
                        },
                    );
                }
                last_emit = Instant::now();
                last_downloaded = downloaded;
                window_start = Instant::now();
                window_downloaded = 0;
            }
        }
    }
    file.flush().await?;
    drop(file);

    if token.is_cancelled() {
        return Err(LauncherError::Cancelled);
    }

    // 无论调用方是否给了期望值都算一遍：算出来的 hash 要写进下载清单，
    // 有期望值时顺带校验。资源类下载本就带 sha1，不会多读一次文件。
    let actual = file_sha1(&part).await?;
    if let Some(expected) = sha1_hex {
        if !actual.eq_ignore_ascii_case(expected) {
            return Err(LauncherError::Checksum(format!(
                "sha1 校验失败：期望 {expected}，实际 {actual}"
            )));
        }
    }

    tokio::fs::rename(&part, dest).await?;
    Ok(Downloaded {
        path: dest.to_path_buf(),
        size: downloaded,
        sha1: actual,
    })
}

/// 流式计算 sha1（同步版）：按 64KB 分块读，避免把整个 jar 读进内存。
/// 下载校验、手动导入文件的哈希、以及其它阻塞场景都用它。
pub fn sha1_file_sync(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

/// 异步包装：哈希是 CPU + 磁盘 IO 混合，丢进阻塞线程池，别占 tokio worker。
async fn file_sha1(path: &Path) -> Result<String> {
    let p = path.to_path_buf();
    tauri::async_runtime::spawn_blocking(move || sha1_file_sync(&p))
        .await
        .map_err(|e| LauncherError::Message(format!("计算 sha1 失败: {e}")))?
}

/// 内部任务直接 await 下载完成（不产生独立 task_id）
pub async fn fetch_to(
    app: &AppHandle,
    task_id: &str,
    name: &str,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<PathBuf> {
    run_download(app, Some(task_id), name, url, dest, sha1_hex, token).await
}

/// 同 `fetch_to`，额外拿到大小与 sha1（下载清单用）
pub async fn fetch_to_full(
    app: &AppHandle,
    task_id: &str,
    name: &str,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<Downloaded> {
    run_download_full(app, Some(task_id), name, url, dest, sha1_hex, token).await
}

/// 静默下载：不发单文件进度（调用方自己汇总整体进度）
pub async fn fetch_to_quiet(
    app: &AppHandle,
    url: &str,
    dest: &Path,
    sha1_hex: Option<&str>,
    token: &CancellationToken,
) -> Result<PathBuf> {
    run_download(app, None, "", url, dest, sha1_hex, token).await
}

pub fn cache_path(file_name: &str) -> PathBuf {
    paths::get().cache_dir.join(file_name)
}
