/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
use reqwest::Client;
use std::sync::OnceLock;
use std::time::Duration;

static CLIENT: OnceLock<Client> = OnceLock::new();

pub fn get() -> &'static Client {
    CLIENT.get_or_init(|| {
        Client::builder()
            .user_agent(format!("AirLauncher/{}", env!("CARGO_PKG_VERSION")))
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(60))
            .pool_max_idle_per_host(16)
            .build()
            .expect("无法构建 HTTP 客户端")
    })
}
