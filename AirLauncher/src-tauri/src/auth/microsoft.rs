/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 微软登录（设备码 / Device Code Flow，对齐 RaphiMC/MinecraftAuth）：
 *   0. MSA: oauth20_connect.srf 拿 device_code + user_code，用户在浏览器输入代码
 *   1. MSA: oauth20_token.srf 轮询换 access/refresh token
 *   2. XBL: user.auth.xboxlive.com user token
 *   3. XSTS: xsts.auth.xboxlive.com XSTS token (rp://api.minecraftservices.com/)
 *   4. MC: api.minecraftservices.com login_with_xbox -> profile
 *
 * 为什么不用授权码 + 本地回调：client id 00000000402b5328 没有注册 http://127.0.0.1:随机端口
 * 这种重定向地址，微软会直接返回 invalid_request: redirect_uri is not valid。
 * 设备码流程不需要任何回调地址，是官方给公共客户端准备的正路。
 */
use crate::error::{LauncherError, Result};
use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio_util::sync::CancellationToken;

/// MinecraftAuth 默认的 Win32 title id（微软官方启动器同款公共客户端）
pub const CLIENT_ID: &str = "00000000402b5328";
/// 设备码 scope。必须带 offline_access，否则不会下发 refresh_token（无法静默续期）。
pub const SCOPE: &str = "XboxLive.signin offline_access";
/// 设备码申请地址（MinecraftAuth MsaEnvironment.LIVE 的 deviceCodeUrl）
pub const DEVICE_CODE_URL: &str = "https://login.live.com/oauth20_connect.srf";
pub const TOKEN_URL: &str = "https://login.live.com/oauth20_token.srf";

const XBL_USER_AUTH: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_AUTHORIZE: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_PROFILE: &str = "https://api.minecraftservices.com/minecraft/profile";

/// MSA 段 token（含 refresh token，用于静默续期）
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MsaToken {
    pub access_token: String,
    pub refresh_token: String,
    /// unix 秒
    pub expires_at: u64,
}

#[derive(Debug, Deserialize)]
struct MsaTokenResponse {
    #[serde(rename = "access_token")]
    access_token: String,
    #[serde(rename = "refresh_token")]
    refresh_token: Option<String>,
    #[serde(rename = "expires_in")]
    expires_in: u64,
}

#[derive(Debug, Deserialize)]
struct XblTokenResponse {
    #[serde(rename = "Token")]
    token: String,
    #[serde(rename = "DisplayClaims")]
    display_claims: serde_json::Value,
}

#[derive(Debug, Deserialize)]
struct McLoginResponse {
    #[serde(rename = "access_token")]
    access_token: String,
    #[serde(rename = "expires_in")]
    expires_in: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McProfile {
    pub id: String,
    pub name: String,
}

/// 设备码授权信息（展示给用户）
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCode {
    /// 轮询用，不需要展示
    pub device_code: String,
    /// 用户在浏览器里要输入的短码，如 NGD6HNS6
    pub user_code: String,
    /// 授权页（https://www.microsoft.com/link）
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

#[derive(Debug, Deserialize)]
struct DeviceCodeResponse {
    device_code: String,
    user_code: String,
    verification_uri: String,
    expires_in: u64,
    #[serde(default = "default_interval")]
    interval: u64,
}

fn default_interval() -> u64 {
    5
}

/// 轮询失败时的错误体
#[derive(Debug, Deserialize)]
struct MsaErrorResponse {
    error: String,
    #[serde(default)]
    error_description: Option<String>,
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// 1. 申请设备码
pub async fn request_device_code() -> Result<DeviceCode> {
    let resp = crate::net::get()
        .post(DEVICE_CODE_URL)
        .form(&[
            ("client_id", CLIENT_ID),
            ("scope", SCOPE),
            ("response_type", "device_code"),
        ])
        .send()
        .await?;

    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(LauncherError::Message(format!(
            "申请设备码失败 (HTTP {status}): {}",
            &body[..body.len().min(300)]
        )));
    }
    let body: DeviceCodeResponse = resp.json().await.map_err(|e| {
        LauncherError::Message(format!("设备码响应解析失败: {e}"))
    })?;
    Ok(DeviceCode {
        device_code: body.device_code,
        user_code: body.user_code,
        verification_uri: body.verification_uri,
        expires_in: body.expires_in.min(900),
        interval: body.interval.max(1),
    })
}

/// 1'. 轮询设备码直到用户完成登录。
///
/// 微软的返回约定（与 MinecraftAuth 一致）：
/// - `authorization_pending`：用户还没输完 → 按 interval 重试
/// - `slow_down`：请求过快 → interval 再 +5s，之后继续
/// - `expired_token` / `authorization_declined` / `bad_verification_code`：终止
pub async fn poll_device_code(code: &DeviceCode, cancel: &CancellationToken) -> Result<MsaToken> {
    let deadline = Instant::now() + Duration::from_secs(code.expires_in);
    let mut interval = Duration::from_secs(code.interval);

    loop {
        if cancel.is_cancelled() {
            return Err(LauncherError::Cancelled);
        }
        if Instant::now() >= deadline {
            return Err(LauncherError::Message("设备码已过期，请重新登录".into()));
        }

        let resp = crate::net::get()
            .post(TOKEN_URL)
            .form(&[
                ("client_id", CLIENT_ID),
                ("grant_type", "device_code"),
                ("device_code", code.device_code.as_str()),
            ])
            .send()
            .await?;

        let status = resp.status();
        let body = resp.text().await?;

        if status.is_success() {
            let parsed: MsaTokenResponse = serde_json::from_str(&body)
                .map_err(|e| LauncherError::Message(format!("MSA token 解析失败: {e}")))?;
            return token_from_body(parsed);
        }

        let err: MsaErrorResponse = serde_json::from_str(&body).unwrap_or(MsaErrorResponse {
            error: "unknown".into(),
            error_description: Some(body.clone()),
        });
        match err.error.as_str() {
            "authorization_pending" => {}
            "slow_down" => interval += Duration::from_secs(5),
            "expired_token" => {
                return Err(LauncherError::Message("设备码已过期，请重新登录".into()))
            }
            "authorization_declined" => {
                return Err(LauncherError::Message("你已拒绝本次授权".into()))
            }
            "bad_verification_code" => {
                return Err(LauncherError::Message("设备码无效，请重新登录".into()))
            }
            _ => {
                return Err(LauncherError::Message(format!(
                    "登录被拒绝 ({}): {}",
                    err.error,
                    err.error_description.unwrap_or_default()
                )))
            }
        }

        // 可取消的等待
        let slept = cancel
            .run_until_cancelled(tokio::time::sleep(interval))
            .await;
        if slept.is_none() {
            return Err(LauncherError::Cancelled);
        }
    }
}

/// 1'. refresh token 续期 MSA token
pub async fn refresh_msa(refresh_token: &str) -> Result<MsaToken> {
    let resp = crate::net::get()
        .post(TOKEN_URL)
        .form(&[
            ("client_id", CLIENT_ID),
            ("refresh_token", refresh_token),
            ("grant_type", "refresh_token"),
        ])
        .send()
        .await?;
    parse_msa_response(resp).await
}

fn token_from_body(body: MsaTokenResponse) -> Result<MsaToken> {
    Ok(MsaToken {
        access_token: body.access_token,
        refresh_token: body.refresh_token.ok_or_else(|| {
            LauncherError::Message("MSA 响应缺少 refresh_token（scope 是否漏了 offline_access？）".into())
        })?,
        expires_at: now_secs() + body.expires_in,
    })
}

async fn parse_msa_response(resp: reqwest::Response) -> Result<MsaToken> {
    let status = resp.status();
    let body: MsaTokenResponse = resp
        .json()
        .await
        .map_err(|e| LauncherError::Message(format!("MSA token 响应解析失败 (HTTP {status}): {e}")))?;
    token_from_body(body)
}

/// 2+3. XBL user token -> XSTS token，返回 (xsts_token, user_hash)
pub async fn xbl_xsts(msa_access_token: &str) -> Result<(String, String)> {
    // XBL user token
    let resp = crate::net::get()
        .post(XBL_USER_AUTH)
        .json(&serde_json::json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={msa_access_token}"),
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        }))
        .send()
        .await?
        .error_for_status()
        .map_err(|e| {
            LauncherError::Message(format!("XBL user token 获取失败: {e}"))
        })?;
    let user: XblTokenResponse = resp.json().await?;
    let uhs = user
        .display_claims
        .get("xui")
        .and_then(|x| x.get(0))
        .and_then(|c| c.get("uhs"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| LauncherError::Message("XBL 响应缺少 uhs".into()))?
        .to_string();

    // XSTS token（Java 版 relying party）
    let resp = crate::net::get()
        .post(XSTS_AUTHORIZE)
        .json(&serde_json::json!({
            "Properties": {
                "SandboxId": "RETAIL",
                "UserTokens": [user.token],
            },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        }))
        .send()
        .await?;

    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        // 2148916233: 无 Xbox 账号
        if body.contains("2148916233") {
            return Err(LauncherError::Message(
                "该微软账号未关联 Xbox 账号，请先在 xbox.com 注册".into(),
            ));
        }
        // 2148916238: 儿童账号限制
        if body.contains("2148916238") {
            return Err(LauncherError::Message(
                "该账号为儿童账号，需在家庭设置中允许加入 Xbox".into(),
            ));
        }
        return Err(LauncherError::Message(format!(
            "XSTS 授权失败 (HTTP {status}): {}",
            &body[..body.len().min(300)]
        )));
    }
    let xsts: XblTokenResponse = resp.json().await?;
    Ok((xsts.token, uhs))
}

/// 4. login_with_xbox 换 Minecraft access token
pub async fn minecraft_login(xsts_token: &str, uhs: &str) -> Result<(String, u64)> {
    let resp = crate::net::get()
        .post(MC_LOGIN)
        .json(&serde_json::json!({
            "identityToken": format!("XBL3.0 x={uhs};{xsts_token}"),
        }))
        .send()
        .await?
        .error_for_status()
        .map_err(|e| LauncherError::Message(format!("Minecraft Services 登录失败: {e}")))?;
    let mc: McLoginResponse = resp.json().await?;
    Ok((mc.access_token, now_secs() + mc.expires_in))
}

/// 拉取玩家档案（uuid + 玩家名）
pub async fn fetch_profile(mc_access_token: &str) -> Result<McProfile> {
    let resp = crate::net::get()
        .get(MC_PROFILE)
        .bearer_auth(mc_access_token)
        .timeout(Duration::from_secs(20))
        .send()
        .await?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(LauncherError::Message(
            "该账号未购买 Minecraft Java 版（无档案）".into(),
        ));
    }
    let profile: McProfile = resp
        .json()
        .await
        .map_err(|e| LauncherError::Message(format!("profile 解析失败: {e}")))?;
    Ok(profile)
}
