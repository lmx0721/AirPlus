/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { onAuthStage } from "$lib/events";
import {
  accountsList,
  authCancel,
  authLogin,
  accountRemove,
  type AccountInfo,
} from "$lib/api/auth";

export const accounts = $state<{ list: AccountInfo[] }>({ list: [] });

/** 登录流程状态 */
export const authState = $state<{
  busy: boolean;
  stage: string;
  message: string;
  /** 设备码：用户需要在浏览器里输入的短码 */
  userCode: string;
  /** 设备码：授权页地址 */
  verificationUri: string;
}>({
  busy: false,
  stage: "",
  message: "",
  userCode: "",
  verificationUri: "",
});

let listening = false;

export async function loadAccounts(): Promise<void> {
  accounts.list = await accountsList();
}

/** 订阅一次登录进度事件 */
export function initAuthListener(): void {
  if (listening) return;
  listening = true;
  onAuthStage((s) => {
    authState.stage = s.stage;
    authState.message = s.message;
    // 设备码只在 code 阶段出现，之后的阶段要清掉，避免显示过期代码
    if (s.stage === "code") {
      authState.userCode = s.userCode ?? "";
      authState.verificationUri = s.verificationUri ?? "";
    } else {
      authState.userCode = "";
    }
    if (s.stage === "done") {
      authState.busy = false;
      loadAccounts();
    } else if (s.stage === "error") {
      authState.busy = false;
    }
  });
}

export async function startLogin(): Promise<void> {
  if (authState.busy) {
    authCancel();
    authState.busy = false;
    return;
  }
  authState.busy = true;
  authState.stage = "waiting";
  authState.message = "正在向微软申请设备码…";
  authState.userCode = "";
  authState.verificationUri = "";
  authLogin();
}

export async function removeAccount(id: string): Promise<void> {
  await accountRemove(id);
  await loadAccounts();
}
