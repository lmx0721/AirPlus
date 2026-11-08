/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 账号持久化 + 微软登录流程编排。accounts.json 为唯一事实来源。
 */
pub mod microsoft;

use crate::error::{LauncherError, Result};
use crate::events;
use crate::paths;
use crate::state::AppState;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use tokio_util::sync::CancellationToken;

/// 一个已登录的微软账号（含令牌缓存，不落明文日志）
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    /// Minecraft uuid（无横线，与 profile.id 一致）
    pub id: String,
    pub name: String,
    pub msa: microsoft::MsaToken,
    /// Minecraft Services access token
    pub mc_access_token: String,
    /// unix 秒
    pub mc_expires_at: u64,
}

/// 前端可见的账号信息（不含令牌）
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountInfo {
    pub id: String,
    pub name: String,
}

// ---------- accounts.json ----------

pub fn load_accounts() -> Vec<Account> {
    std::fs::read(&paths::get().accounts_file)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

pub fn save_accounts(accounts: &[Account]) -> Result<()> {
    let tmp = paths::get().accounts_file.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(accounts)?)?;
    std::fs::rename(&tmp, &paths::get().accounts_file)?;
    Ok(())
}

pub fn upsert(account: Account) -> Result<()> {
    let mut accounts = load_accounts();
    accounts.retain(|a| a.id != account.id);
    accounts.push(account);
    save_accounts(&accounts)
}

pub fn remove(id: &str) -> Result<()> {
    let mut accounts = load_accounts();
    accounts.retain(|a| a.id != id);
    save_accounts(&accounts)
}

pub fn get(id: &str) -> Result<Account> {
    load_accounts()
        .into_iter()
        .find(|a| a.id == id)
        .ok_or_else(|| LauncherError::Message(format!("账号 {id} 不存在")))
}

/// 启动前取一个可用账号：token 临期（<60s）则静默刷新
pub async fn ensure_fresh(account: &Account) -> Result<Account> {
    let mut acc = account.clone();
    if acc.mc_expires_at.saturating_sub(now_secs()) < 60 {
        acc = refresh(acc).await?;
    }
    Ok(acc)
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// 静默刷新：MSA refresh token -> XBL -> XSTS -> MC token -> profile
pub async fn refresh(account: Account) -> Result<Account> {
    let msa = microsoft::refresh_msa(&account.msa.refresh_token).await?;
    let (xsts, uhs) = microsoft::xbl_xsts(&msa.access_token).await?;
    let (mc_token, mc_expires_at) = microsoft::minecraft_login(&xsts, &uhs).await?;
    let profile = microsoft::fetch_profile(&mc_token).await?;

    let updated = Account {
        id: profile.id,
        name: profile.name,
        msa,
        mc_access_token: mc_token,
        mc_expires_at,
    };
    upsert(updated.clone())?;
    Ok(updated)
}

// ---------- 交互登录 ----------

fn emit_stage(app: &AppHandle, stage: &str, message: &str) {
    let _ = app.emit(
        events::AUTH_STAGE,
        serde_json::json!({ "stage": stage, "message": message }),
    );
}

/// 完整交互式登录（后台任务内调用），设备码流程
pub async fn login_interactive(app: &AppHandle, token: &CancellationToken) -> Result<Account> {
    emit_stage(app, "waiting", "正在向微软申请设备码…");
    let code = microsoft::request_device_code().await?;

    // 设备码要显示在界面上（用户需要看着输），所以单独发一条带结构化字段的事件
    let _ = app.emit(
        events::AUTH_STAGE,
        serde_json::json!({
            "stage": "code",
            "message": format!("在浏览器打开 {} 并输入下方代码", code.verification_uri),
            "userCode": code.user_code,
            "verificationUri": code.verification_uri,
        }),
    );
    // 顺手拉起浏览器；拉不起来也不影响流程（用户可手动复制链接）
    let _ = tauri_plugin_opener::open_url(&code.verification_uri, None::<&str>);

    let msa = microsoft::poll_device_code(&code, token).await?;
    emit_stage(app, "msa", "已收到微软授权，正在换取令牌…");

    emit_stage(app, "xbl", "Xbox Live 授权…");
    let (xsts, uhs) = microsoft::xbl_xsts(&msa.access_token).await?;

    emit_stage(app, "minecraft", "登录 Minecraft Services…");
    let (mc_token, mc_expires_at) = microsoft::minecraft_login(&xsts, &uhs).await?;
    let profile = microsoft::fetch_profile(&mc_token).await?;

    let account = Account {
        id: profile.id,
        name: profile.name,
        msa,
        mc_access_token: mc_token,
        mc_expires_at,
    };
    upsert(account.clone())?;
    emit_stage(app, "done", &format!("登录成功: {}", account.name));
    Ok(account)
}

// ---------- Tauri 命令 ----------

#[tauri::command]
pub async fn auth_login(app: tauri::AppHandle) {
    let token = CancellationToken::new();
    app.state::<AppState>()
        .cancels
        .lock()
        .unwrap()
        .insert("auth".into(), token.clone());

    tauri::async_runtime::spawn(async move {
        let result = login_interactive(&app, &token).await;
        app.state::<AppState>().cancels.lock().unwrap().remove("auth");
        if let Err(e) = &result {
            if !matches!(e, LauncherError::Cancelled) {
                emit_stage(&app, "error", &e.to_string());
            }
        }
    });
}

#[tauri::command]
pub async fn auth_cancel(app: tauri::AppHandle) {
    let token = app.state::<AppState>().cancels.lock().unwrap().get("auth").cloned();
    if let Some(token) = token {
        token.cancel();
    }
}

/// 读 accounts.json。Tauri 2 的非 async 命令跑在主线程，故下沉到阻塞线程池。
#[tauri::command]
pub async fn accounts_list() -> Result<Vec<AccountInfo>> {
    tauri::async_runtime::spawn_blocking(|| {
        load_accounts()
            .into_iter()
            .map(|a| AccountInfo { id: a.id, name: a.name })
            .collect()
    })
    .await
    .map_err(|e| LauncherError::Message(format!("读取账号失败: {e}")))
}

#[tauri::command]
pub async fn account_remove(id: String) -> Result<()> {
    tauri::async_runtime::spawn_blocking(move || remove(&id))
        .await
        .map_err(|e| LauncherError::Message(format!("删除账号失败: {e}")))?
}

#[tauri::command]
pub async fn account_refresh(id: String) -> Result<AccountInfo> {
    let account = get(&id)?;
    let refreshed = refresh(account).await?;
    Ok(AccountInfo {
        id: refreshed.id,
        name: refreshed.name,
    })
}
