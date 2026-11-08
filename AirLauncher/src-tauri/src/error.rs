/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
use serde::{ser::Serializer, Serialize};

#[derive(Debug, thiserror::Error)]
pub enum LauncherError {
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("数据序列化错误: {0}")]
    Json(#[from] serde_json::Error),
    #[error("网络错误: {0}")]
    Network(#[from] reqwest::Error),
    #[error("配置错误: {0}")]
    Config(String),
    #[error("校验失败: {0}")]
    Checksum(String),
    #[error("已取消")]
    Cancelled,
    #[error("{0}")]
    Message(String),
}

impl Serialize for LauncherError {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

pub type Result<T> = std::result::Result<T, LauncherError>;
