/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 客户端本体（mods/ 里的 AirPlus-*.jar）的状态查询与激活。
 */
import { invoke } from "@tauri-apps/api/core";

export interface ClientStatus {
  /** mods 里是否已有任意一个 AirPlus 客户端 */
  installed: boolean;
  fileName: string | null;
  /** 从文件名推断的 release 标签，如 b4 */
  tag: string | null;
}

export function clientStatus(): Promise<ClientStatus> {
  return invoke("client_status");
}

/** 把某个已下载的 jar 设为唯一生效版本（会删掉其余客户端 jar） */
export function clientActivate(name: string): Promise<void> {
  return invoke("client_activate", { name });
}
