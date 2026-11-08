/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { invoke } from "@tauri-apps/api/core";

export interface GhAsset {
  id: number;
  name: string;
  size: number;
  browser_download_url: string;
}

export interface GhRelease {
  tag_name: string;
  name: string | null;
  body: string | null;
  published_at: string | null;
  prerelease: boolean;
  assets: GhAsset[];
}

export function listReleases(): Promise<GhRelease[]> {
  return invoke("github_list_releases");
}

/** tag 会写进下载清单，用来记录「下过哪些客户端版本」 */
export function downloadAsset(asset: GhAsset, tag: string | null = null): Promise<string> {
  return invoke("github_download_asset", { asset, tag });
}

export function downloadCancel(taskId: string): Promise<void> {
  return invoke("download_cancel", { taskId });
}
