/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { invoke } from "@tauri-apps/api/core";
import { settingsGet, settingsSet, type Settings } from "$lib/api/app";

export const settings = $state<Settings>({
  windowEffect: "acrylic",
  ramMb: 2048,
  extraJvmArgs: [],
  downloadConcurrency: 8,
  githubPat: null,
  mirror: null,
  activeAccountId: null,
});

/*
 * 用「进行中的 Promise」做缓存，而不是布尔 loaded 守卫。
 * 旧写法在 await 之前就把 loaded 置 true，导致第二个调用者立即返回、
 * 读到 activeAccountId = null —— 首页于是永远显示「离线模式」。
 */
let loadPromise: Promise<void> | null = null;

/** 把窗口效果同步到 DWM 与 CSS 变量（不透明模式下要靠 data 属性切回纯色底） */
export function applyWindowEffect(effect: string): void {
  document.documentElement.dataset.windowEffect = effect;
  void invoke("window_set_effect", { effect }).catch(() => {
    /* 效果不受支持时静默降级为当前外观，不打断用户操作 */
  });
}

export function loadSettings(): Promise<void> {
  if (!loadPromise) {
    loadPromise = settingsGet()
      .then((s) => {
        Object.assign(settings, s);
        applyWindowEffect(settings.windowEffect);
      })
      .catch((e) => {
        loadPromise = null; // 失败允许重试
        throw e;
      });
  }
  return loadPromise;
}

export async function saveSettings(): Promise<void> {
  await settingsSet($state.snapshot(settings));
  applyWindowEffect(settings.windowEffect);
}
