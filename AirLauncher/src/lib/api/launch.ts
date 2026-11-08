/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { invoke } from "@tauri-apps/api/core";
import type { LaunchStatus } from "$lib/events";

export interface LaunchOptions {
  playerName: string;
  /** MSA 账号 id；Some = 正版账号（临期自动静默刷新） */
  accountId: string | null;
}

export function launchStart(options: LaunchOptions): Promise<void> {
  return invoke("launch_start", { options });
}

export function launchCancel(): Promise<void> {
  return invoke("launch_cancel");
}

export function launchStatus(): Promise<LaunchStatus> {
  return invoke("launch_status");
}
