/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { invoke } from "@tauri-apps/api/core";

export interface AccountInfo {
  id: string;
  name: string;
}

export function authLogin(): Promise<void> {
  return invoke("auth_login");
}

export function authCancel(): Promise<void> {
  return invoke("auth_cancel");
}

export function accountsList(): Promise<AccountInfo[]> {
  return invoke("accounts_list");
}

export function accountRemove(id: string): Promise<void> {
  return invoke("account_remove", { id });
}

export function accountRefresh(id: string): Promise<AccountInfo> {
  return invoke("account_refresh", { id });
}
