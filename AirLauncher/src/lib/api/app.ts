/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { invoke } from "@tauri-apps/api/core";

export interface AppPathsInfo {
  root: string;
  instancesDir: string;
  instanceDir: string;
  modsDir: string;
  jreDir: string;
  cacheDir: string;
  settingsFile: string;
  accountsFile: string;
  /** 下载清单（客户端版本 + 模组 + sha1） */
  manifestFile: string;
}

export interface Settings {
  /** 已废弃：窗口为 opaque + CSS 渐变，保留字段仅为后端 serde 兼容 */
  windowEffect: "acrylic" | "mica" | "none";
  ramMb: number;
  extraJvmArgs: string[];
  downloadConcurrency: number;
  githubPat: string | null;
  mirror: string | null;
  activeAccountId: string | null;
}

export function pathsGet(): Promise<AppPathsInfo> {
  return invoke("paths_get");
}

/**
 * 用系统默认程序打开数据目录内的路径。
 * 走后端 opener（Rust 侧不受 ACL 限制），前端直接调插件会报 Not allowed to open path。
 */
export function openDataPath(path: string): Promise<void> {
  return invoke("open_path", { path });
}

export function settingsGet(): Promise<Settings> {
  return invoke("settings_get");
}

export function settingsSet(settings: Settings): Promise<void> {
  return invoke("settings_set", { settings });
}
