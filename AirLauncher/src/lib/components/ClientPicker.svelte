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
  import { modsList, optifineInstall, optifineVersions, type OptifineVersion } from "$lib/api/mods";
  import { onDownloadProgress, type DownloadProgress } from "$lib/events";
  import { formatBytes } from "$lib/stores/downloads.svelte";
  import type { UnlistenFn } from "@tauri-apps/api/event";
  import { untrack } from "svelte";

  interface ClientPickerProps {
    open?: boolean;
    /** 下载完成后是否继续启动游戏（从「启动游戏」按钮进来的场景） */
    startAfterPick?: boolean;
    onclose?: () => void;
    onready?: () => void;
  }

  let {
    open = false,
    startAfterPick = false,
    onclose,
    onready,
  }: ClientPickerProps = $props();

  let releases = $state<GhRelease[]>([]);
  let loading = $state(false);
  let loadError = $state<string | null>(null);
  let selectedTag = $state<string | null>(null);

  let downloading = $state(false);
  let progress = $state<DownloadProgress | null>(null);
  let dlError = $state<string | null>(null);
  /** 当前正在做哪一步（客户端 / 推荐模组），显示在进度条上方 */
  let stage = $state("");

  /** 推荐模组：OptiFine 是 1.8.9 客户端最常见的一项，装客户端时顺手带上 */
  let wantOptifine = $state(true);
  let ofVersion = $state<OptifineVersion | null>(null);
  let ofLoading = $state(false);
  /** OptiFine 列表拉不到时的说明（只影响勾选框，不拦下载） */
  let ofUnavailable = $state<string | null>(null);
  /** OptiFine 装失败时的提示（客户端本身已装好，不阻塞启动） */
  let ofWarn = $state<string | null>(null);

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
    untrack(() => {
      void load();
      void loadOptifine();
    });
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

  /** 拉一次 OptiFine 版本列表，默认勾选官网最新的正式版（非 preview） */
  async function loadOptifine() {
    if (ofLoading || ofVersion) return;
    ofLoading = true;
    ofUnavailable = null;
    try {
      const list = await optifineVersions();
      ofVersion = list.find((v) => !v.preview) ?? list[0] ?? null;
      if (!ofVersion) {
        ofUnavailable = "官网暂时没有可用的 1.8.9 版本";
        wantOptifine = false;
      }
    } catch (e) {
      // 官网改版/被墙都可能失败，只是勾选框不可用，别影响客户端下载
      ofUnavailable = String(e);
      wantOptifine = false;
    } finally {
      ofLoading = false;
    }
  }

  /** 轮询 mods 目录确认推荐模组落地（OptiFine 走后台任务，没有 await 的返回值） */
  function waitModInstalled(
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
          const list = await modsList();
          if (list.some((m) => m.fileName === fileName)) {
            resolve();
            return;
          }
        } catch {
          /* 单次查询失败不影响继续等待 */
        }
        if (Date.now() - started > timeoutMs) {
          reject(new Error("OptiFine 下载超时，请到模组页重试"));
          return;
        }
        setTimeout(tick, 400);
      };
      void tick();
    });
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
    ofWarn = null;
    progress = null;
    stage = "正在下载客户端";

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

      stage = "正在启用客户端";
      await clientActivate(asset.name);

      // 推荐模组放在客户端之后装：OptiFine 是普通 mod jar，不依赖客户端是否已就绪，
      // 但顺序反过来时若 OptiFine 失败会让用户误以为客户端也没装上。
      if (wantOptifine && ofVersion) {
        const ofFile = `${ofVersion.fileName}.jar`;
        stage = `正在下载 OptiFine ${ofVersion.display}`;
        progress = null;
        try {
          // 复用同一个 taskId 变量：进度监听按 taskId 过滤，任务是一个接一个跑的
          taskId = await optifineInstall(ofVersion.fileName);
          await waitModInstalled(ofFile, () => failMsg);
        } catch (e) {
          // 只是推荐项，装不上不该拦住启动
          ofWarn = `OptiFine 安装失败：${String(e)}`;
        }
      }

      stage = "";
      downloading = false;
      onready?.();
      onclose?.();
    } catch (e) {
      dlError = String(e);
      stage = "";
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
    onclick={(e) => {
      // 只有点在遮罩本身（而不是弹窗内部）才关闭，省掉弹窗上的 stopPropagation
      if (!downloading && e.target === e.currentTarget) onclose?.();
    }}
  >
    <div class="dialog card" role="dialog" aria-modal="true" tabindex="-1">
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

        <div class="field">
          <span class="label">推荐模组</span>
          <label class="check">
            <input
              type="checkbox"
              bind:checked={wantOptifine}
              disabled={downloading || ofLoading || !!ofUnavailable}
            />
            <span>
              OptiFine{ofVersion ? ` ${ofVersion.display}` : ""}
              <em>· 高清修复，提升帧数并兼容光影</em>
            </span>
          </label>
          {#if ofLoading}
            <span class="note">正在查询 OptiFine 版本…</span>
          {:else if ofUnavailable}
            <span class="note">暂时装不了 OptiFine（{ofUnavailable}），可稍后到「模组」页安装</span>
          {/if}
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
            {#if stage}
              <div class="stage">{stage}</div>
            {/if}
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

        {#if ofWarn}
          <div class="state warn">{ofWarn}</div>
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
  .check {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 8px 10px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.03);
    font-size: 12.5px;
    line-height: 1.5;
    cursor: pointer;
    user-select: none;
  }
  .check input {
    margin: 2px 0 0;
    accent-color: var(--accent);
    flex: 0 0 auto;
  }
  .check em {
    font-style: normal;
    color: var(--text-muted);
  }
  .note {
    font-size: 11.5px;
    color: var(--text-muted);
  }
  .stage {
    font-size: 12px;
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
  .state.warn {
    color: var(--warning, #e0a33e);
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
