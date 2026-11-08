/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 */
import { onDownloadProgress, type DownloadProgress } from "$lib/events";

export type TaskState = "running" | "done" | "error" | "cancelled";

export interface DlTask {
  taskId: string;
  name: string;
  downloaded: number;
  total: number;
  speedBps: number;
  state: TaskState;
  error: string | null;
}

export const downloads = $state<{ tasks: DlTask[] }>({ tasks: [] });

let listening = false;

/** 同一帧内到达的进度先攒着，rAF 里一次性合并，避免每条事件都触发一次 UI 失效 */
const pending = new Map<string, DownloadProgress>();
let frame = 0;
const removing = new Set<string>();

function flush(): void {
  frame = 0;
  if (pending.size === 0) return;

  const next = downloads.tasks.slice();
  for (const p of pending.values()) {
    const i = next.findIndex((t) => t.taskId === p.taskId);
    if (i === -1) {
      next.push({ ...p });
    } else {
      next[i] = { ...next[i], ...p };
    }

    // 终态任务 5 秒后移除（同一个任务只排一次）
    if (p.state !== "running" && !removing.has(p.taskId)) {
      removing.add(p.taskId);
      setTimeout(() => {
        removing.delete(p.taskId);
        downloads.tasks = downloads.tasks.filter((t) => t.taskId !== p.taskId);
      }, 5000);
    }
  }
  pending.clear();
  // 整体替换往往比逐条改下标更省：只触发一次失效
  downloads.tasks = next;
}

export function initDownloadEvents(): void {
  if (listening) return;
  listening = true;

  void onDownloadProgress((batch) => {
    for (const p of batch) pending.set(p.taskId, p);
    if (frame === 0) frame = requestAnimationFrame(flush);
  });
}

export function formatBytes(bytes: number): string {
  if (bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}
