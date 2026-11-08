/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
export type ViewId = "home" | "versions" | "mods" | "accounts" | "settings";

export const view = $state<{ current: ViewId }>({ current: "home" });
