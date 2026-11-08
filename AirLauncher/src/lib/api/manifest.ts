/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 下载清单（launcher-manifest.json）的前端类型。
 * 后端在每次下载成功后写入，记录客户端版本与模组的来源和 sha1。
 */
import { invoke } from "@tauri-apps/api/core";

export interface ClientRecord {
  fileName: string;
  /** GitHub release 标签，如 b4 */
  tag: string | null;
  size: number;
  sha1: string | null;
  url: string | null;
  /** ISO8601 UTC */
  installedAt: string;
  /** 是否是 mods 里当前生效的客户端 */
  active: boolean;
  /** 文件是否还在 mods 目录 */
  present: boolean;
}

export interface ModRecord {
  fileName: string;
  size: number;
  sha1: string | null;
  /** modrinth | optifine | manual */
  source: string;
  projectId: string | null;
  versionId: string | null;
  versionNumber: string | null;
  url: string | null;
  installedAt: string;
  present: boolean;
}

export interface Manifest {
  version: number;
  clients: ClientRecord[];
  mods: ModRecord[];
}

export function manifestGet(): Promise<Manifest> {
  return invoke("manifest_get");
}
