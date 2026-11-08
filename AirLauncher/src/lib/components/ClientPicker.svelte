<!--
  AirLauncher
  A Tauri-based launcher for the AirPlus Minecraft client.
  https://github.com/lmx0721/AirPlus

  客户端版本选择弹窗。

  两个入口：
  1) 首页点「启动游戏」但 mods 里没有任何 AirPlus 客户端 → 弹出来让用户选一个版本下载；
  2) 首页「客户端」卡片被点击 → 主动切换版本。

  下载走统一下载管线（有进度、断点续传、重试），完成后调 client_activate
  把该 jar 设为唯一生效客户端（旧的会被清掉，避免 Forge 同时加载多个客户端）。
-->
<script lang="ts">
  import { listReleases, downloadAsset, type GhRelease, type GhAsset } from "$lib/api/github";
  import { clientActivate, clientStatus } from "$lib/api/client";
  import { onDownloadProgress, type DownloadProgress } from "$lib/events";
  import { formatBytes } from "$lib/stores/downloads.svelte";
  import type { UnlistenFn } from "@tauri-apps/api/event";
  import { untrack } from "svelte";

  let {
    open = false,
    /** 下载完成后是否继续启动游戏（从「启动游戏」按钮进来的场景） */
    startAfterPick = false,
    onclose,
    onready,
  } = $props<{
    open: boolean;
    startAfterPick?: boolean;
    onclose?: () => void;
    onready?: () => void;
  }>();

  let releases = $state<GhRelease[]>([]);
  let loading = $state(false);
  let loadError = $state<string | null>(null);
  let selectedTag = $state<string | null>(null);

  let downloading = $state(false);
  let progress = $state<DownloadProgress | null>(null);
  let dlError = $state<string | null>(null);

  /** release 里挑一个可用的客户端 jar（排除 sources/javadoc 之类的附属包） */
  function pickAsset(r: GhRelease): GhAsset | null {
    return (
      r.assets.find((a) => {
        const n = a.name.toLowerCase();
        return n.endsWith(".jar") && !n.includes("sources") && !n.includes("javadoc");
      }) ?? null
    );
  }

  const available = $derived(releases.filter((r) => pickAsset(r) !== null));
  const selected = $derived(available.find((r) => r.tag_name === selectedTag) ?? null);
  const selectedAsset = $derived(selected ? pickAsset(selected) : null);

  // open 变 true 时刷新列表（每次打开都重新拉，避免看到过期的版本）。
  // untrack 是必须的：load() 内部会读写 loading/releases 等状态，
  // 若被 effect 追踪，loading 一落回 false 就会再次触发加载，形成死循环。
  $effect(() => {
    if (!open) return;
    untrack(() => void load());
  });

  async function load() {
    if (loading) return;
    loading = true;
    loadError = null;
    try {
      const list = await listReleases();
      releases = list;
      // 默认选中第一个正式版，没有正式版就取第一个
      const first = list.find((r) => !r.prerelease && pickAsset(r)) ?? list.find((r) => pickAsset(r));
      selectedTag = first?.tag_name ?? null;
    } catch (e) {
      loadError = String(e);
    } finally {
      loading = false;
    }
  }

  /** 轮询目录判断文件是否真的落地：下载完成事件有极小概率早于监听注册 */
  function waitInstalled(
    fileName: string,
    failed: () => string | null,
    timeoutMs = 10 * 60 * 1000,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const started = Date.now();
      const tick = async () => {
        const f = failed();
        if (f) {
          reject(new Error(f));
          return;
        }
        try {
          const st = await clientStatus();
          if (st.installed && st.fileName === fileName) {
            resolve();
            return;
          }
        } catch {
          /* 单次查询失败不影响继续等待 */
        }
        if (Date.now() - started > timeoutMs) {
          reject(new Error("下载超时，请检查网络后重试"));
          return;
        }
        setTimeout(tick, 400);
      };
      void tick();
    });
  }

  async function startDownload() {
    const rel = selected;
    const asset = selectedAsset;
    if (!rel || !asset) return;

    downloading = true;
    dlError = null;
    progress = null;

    let taskId = "";
    let failMsg: string | null = null;
    let un: UnlistenFn | null = null;

    try {
      // 先挂监听，避免漏掉最开始那几条进度
      un = await onDownloadProgress((batch) => {
        if (!taskId) return;
        const p = batch.find((x) => x.taskId === taskId);
        if (!p) return;
        progress = p;
        if (p.state === "error") failMsg = p.error ?? "下载失败";
        else if (p.state === "cancelled") failMsg = "下载已取消";
      });

      taskId = await downloadAsset(asset, rel.tag_name);
      await waitInstalled(asset.name, () => failMsg);
      await clientActivate(asset.name);
      downloading = false;
      onready?.();
      onclose?.();
    } catch (e) {
      dlError = String(e);
      downloading = false;
    } finally {
      un?.();
    }
  }

  function pct(): number {
    if (!progress || progress.total <= 0) return 0;
    return Math.min(100, (progress.downloaded / progress.total) * 100);
  }
</script>

{#if open}
  <div
    class="mask"
    role="presentation"
    onclick={() => {
      if (!downloading) onclose?.();
    }}
  >
    <div class="dialog card" role="dialog" aria-modal="true" onclick={(e) => e.stopPropagation()}>
      <header>
        <h2>{startAfterPick ? "还没有安装 AirPlus 客户端" : "切换客户端版本"}</h2>
        <p class="hint">
          {startAfterPick
            ? "首次启动需要先下载客户端本体（jar）。选一个版本，下载完成后会自动开始启动。"
            : "下载后会替换掉 mods 里现有的客户端，保证只有一个版本生效。"}
        </p>
      </header>

      {#if loading}
        <div class="state">正在获取版本列表…</div>
      {:else if loadError}
        <div class="state error">获取版本列表失败：{loadError}</div>
        <div class="actions">
          <button class="btn" onclick={load}>重试</button>
          <button class="btn" onclick={() => onclose?.()}>关闭</button>
        </div>
      {:else if available.length === 0}
        <div class="state">仓库里没有可下载的客户端版本。</div>
        <div class="actions">
          <button class="btn" onclick={() => onclose?.()}>关闭</button>
        </div>
      {:else}
        <div class="field">
          <span class="label">版本</span>
          <select class="input select" bind:value={selectedTag} disabled={downloading}>
            {#each available as r (r.tag_name)}
              <option value={r.tag_name}>
                {r.tag_name}{r.prerelease ? "（预发布）" : ""} · {r.published_at?.slice(0, 10) ??
                  "未知日期"}
              </option>
            {/each}
          </select>
        </div>

        {#if selected}
          <div class="meta">
            <span>{selected.name ?? selected.tag_name}</span>
            <span class="dot">·</span>
            <span>{selectedAsset ? formatBytes(selectedAsset.size) : "—"}</span>
            <span class="dot">·</span>
            <span>{selectedAsset?.name ?? "无 jar"}</span>
          </div>
          {#if selected.body}
            <pre class="notes">{selected.body.slice(0, 800)}</pre>
          {/if}
        {/if}

        {#if downloading}
          <div class="progress">
            <div class="bar">
              <div class="fill" style="transform: scaleX({pct() / 100})"></div>
            </div>
            <div class="pline">
              <span>
                {progress ? `${formatBytes(progress.downloaded)} / ${formatBytes(progress.total)}` : "准备中…"}
              </span>
              <span>
                {progress && progress.speedBps > 0 ? `${formatBytes(progress.speedBps)}/s` : ""}
              </span>
            </div>
          </div>
        {/if}

        {#if dlError}
          <div class="state error">{dlError}</div>
        {/if}

        <div class="actions">
          <button class="btn" onclick={() => onclose?.()} disabled={downloading}>取消</button>
          <button class="btn primary" onclick={startDownload} disabled={downloading || !selectedAsset}>
            {downloading ? "下载中…" : startAfterPick ? "下载并启动" : "下载并切换"}
          </button>
        </div>
      {/if}
    </div>
  </div>
{/if}

<style>
  .mask {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 60;
  }
  .dialog {
    width: min(560px, calc(100vw - 80px));
    max-height: min(76vh, 640px);
    padding: 20px 22px;
    display: flex;
    flex-direction: column;
    gap: 14px;
    overflow-y: auto;
    background: var(--window-strong, #1b1a1f);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
  }
  header h2 {
    margin: 0 0 6px;
    font-size: 17px;
  }
  .hint {
    margin: 0;
    font-size: 12px;
    color: var(--text-muted);
    line-height: 1.6;
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .label {
    font-size: 12px;
    color: var(--text-muted);
  }
  .select {
    width: 100%;
    cursor: pointer;
  }
  .select option {
    background: #1b1a1f;
    color: var(--text);
  }
  .meta {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    font-size: 12px;
    color: var(--text-muted);
  }
  .dot {
    opacity: 0.5;
  }
  .notes {
    margin: 0;
    max-height: 148px;
    overflow-y: auto;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    background: rgba(8, 8, 12, 0.4);
    border: 1px solid var(--line);
    font-family: var(--font);
    font-size: 12px;
    line-height: 1.65;
    color: var(--text-muted);
    white-space: pre-wrap;
    word-break: break-word;
    user-select: text;
    -webkit-user-select: text;
  }
  .state {
    font-size: 12px;
    color: var(--text-muted);
    padding: 6px 0;
  }
  .state.error {
    color: var(--danger, #e5484d);
    word-break: break-word;
  }
  .progress {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .bar {
    height: 6px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.08);
    overflow: hidden;
  }
  .fill {
    height: 100%;
    width: 100%;
    background: var(--accent);
    transform-origin: left center;
    transform: scaleX(0);
    /* 进度每 100ms 更新一次，用 transform 只做合成层，不触发布局 */
    transition: transform 0.1s linear;
  }
  .pline {
    display: flex;
    justify-content: space-between;
    font-size: 11px;
    color: var(--text-muted);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 2px;
  }
</style>
