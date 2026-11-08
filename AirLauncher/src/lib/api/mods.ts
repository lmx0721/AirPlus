/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { invoke } from "@tauri-apps/api/core";

export interface ModrinthHit {
  projectId: string;
  title: string;
  slug: string;
  description: string;
  downloads: number;
  iconUrl: string | null;
}

export interface ModrinthVersion {
  id: string;
  versionNumber: string;
  name: string;
  datePublished: string;
  fileName: string;
  fileUrl: string;
  fileSize: number;
}

export interface OptifineVersion {
  fileName: string;
  display: string;
}

export interface ModFile {
  fileName: string;
  size: number;
}

export function modrinthSearch(query: string): Promise<ModrinthHit[]> {
  return invoke("modrinth_search", { query });
}

export function modrinthVersions(projectId: string): Promise<ModrinthVersion[]> {
  return invoke("modrinth_versions", { projectId });
}

export function modrinthInstall(version: ModrinthVersion): Promise<string> {
  return invoke("modrinth_install", { version });
}

export function modsList(): Promise<ModFile[]> {
  return invoke("mods_list");
}

export function modDelete(fileName: string): Promise<void> {
  return invoke("mod_delete", { fileName });
}

export function optifineVersions(): Promise<OptifineVersion[]> {
  return invoke("optifine_versions");
}

export function optifineInstall(fileName: string): Promise<string> {
  return invoke("optifine_install", { fileName });
}

export function modImportFile(sourcePath: string): Promise<string> {
  return invoke("mod_import_file", { sourcePath });
}
