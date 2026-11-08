/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
use crate::config::Settings;
use std::collections::HashMap;
use std::sync::Mutex;
use tokio_util::sync::CancellationToken;

/// 全局共享状态。互斥锁内只做读写，不做 IO。
pub struct AppState {
    pub settings: Mutex<Settings>,
    /// task_id -> 取消令牌（下载/登录/安装等所有可取消任务）
    pub cancels: Mutex<HashMap<String, CancellationToken>>,
    /// 当前游戏进程 PID（用于 taskkill /T 结束进程树）
    pub launch_pid: Mutex<Option<u32>>,
}

impl AppState {
    pub fn new(settings: Settings) -> Self {
        Self {
            settings: Mutex::new(settings),
            cancels: Mutex::new(HashMap::new()),
            launch_pid: Mutex::new(None),
        }
    }

    /// 读取当前游戏进程 PID。
    ///
    /// 一律走本方法，不要在业务代码里直接 `launch_pid.lock()`：
    /// 结构体字段初始化表达式里的 MutexGuard 会活到整条语句结束，
    /// 若同一表达式里再加锁一次，std 的 Mutex 不可重入 → 同线程永久死锁。
    /// poisoned 也取出内部值，避免一次死锁之后所有 unwrap 连环 panic。
    pub fn launch_pid(&self) -> Option<u32> {
        *self.launch_pid.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn set_launch_pid(&self, pid: Option<u32>) {
        *self.launch_pid.lock().unwrap_or_else(|e| e.into_inner()) = pid;
    }

    /// 取出并清空 PID（等价于 `Option::take`，保持单次加锁的原子性）。
    pub fn take_launch_pid(&self) -> Option<u32> {
        self.launch_pid
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
    }

    /// 与 `settings.lock()` 等价的读快照，同样容忍 poisoned。
    pub fn settings_snapshot(&self) -> Settings {
        self.settings
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }
}
