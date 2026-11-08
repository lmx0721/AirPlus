<script lang="ts">
  import { settings, saveSettings, loadSettings, applyWindowEffect } from "$lib/stores/settings.svelte";
  import { pathsGet, openDataPath, type AppPathsInfo } from "$lib/api/app";
  import { manifestGet, type Manifest } from "$lib/api/manifest";

  let { active = false } = $props();

  let saving = $state(false);
  let savedAt = $state("");
  let paths = $state<AppPathsInfo | null>(null);

  // 下载清单：下过哪些客户端版本、装过哪些模组（含 sha1）
  let manifest = $state<Manifest | null>(null);
  // 打开目录/文件失败时的提示（以前静默失败，看着像按钮没反应）
  let openError = $state("");

  // 镜像输入框草稿（自定义源用）
  let mirrorDraft = $state("");
  // 下载源选择：官方 / BMCLAPI / 自定义
  let mirrorKind = $state<MirrorKind>("official");
  // JVM 参数草稿（一行一个）
  let jvmDraft = $state("");

  let initialized = $state(false);
  $effect(() => {
    if (!active || initialized) return;
    initialized = true;
    pathsGet().then((p) => (paths = p));
    void refreshManifest();
    // 等设置真正加载完再回填草稿，否则会读到默认值
    void loadSettings().then(() => {
      mirrorDraft = settings.mirror ?? "";
      mirrorKind = detectMirror(settings.mirror);
      jvmDraft = settings.extraJvmArgs.join("\n");
    });
  });

  async function refreshManifest() {
    try {
      manifest = await manifestGet();
    } catch (e) {
      openError = `读取下载记录失败：${String(e)}`;
    }
  }

  /**
   * 用系统默认程序打开路径（走后端 opener，不走前端插件 ACL）。
   * 失败必须弹出来，不然表现就是「点了没反应」。
   */
  async function openTarget(path: string) {
    openError = "";
    try {
      await openDataPath(path);
    } catch (e) {
      openError = `打开失败：${String(e)}`;
    }
  }

  function sizeText(bytes: number): string {
    if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${bytes} B`;
  }

  function shortSha1(sha1: string | null): string {
    return sha1 ? `${sha1.slice(0, 8)}…` : "无";
  }

  function dateText(iso: string): string {
    // 后端存 UTC，这里转本地时间显示
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
  }

  const EFFECTS = [
    { value: "acrylic", label: "亚克力（半透明毛玻璃）" },
    { value: "mica", label: "云母（Win11 系统材质）" },
    { value: "none", label: "不透明（兼容性最好）" },
  ];

  type MirrorKind = "official" | "bmclapi" | "custom";
  const BMCLAPI = "https://bmclapi2.bangbang93.com";
  const MIRRORS: { value: MirrorKind; label: string; url: string | null }[] = [
    { value: "official", label: "官方源（Mojang 直连）", url: null },
    { value: "bmclapi", label: "BMCLAPI 镜像（国内加速）", url: BMCLAPI },
    { value: "custom", label: "自定义地址…", url: null },
  ];

  /** 从已保存的地址反推属于哪一档 */
  function detectMirror(url: string | null): MirrorKind {
    if (!url) return "official";
    return url.replace(/\/+$/, "") === BMCLAPI ? "bmclapi" : "custom";
  }

  function onEffectChange(e: Event) {
    const value = (e.currentTarget as HTMLSelectElement).value as typeof settings.windowEffect;
    settings.windowEffect = value;
    applyWindowEffect(value); // 立即预览，保存时落盘
  }

  async function save() {
    saving = true;
    try {
      settings.mirror = selectedMirror();
      settings.extraJvmArgs = jvmDraft
        .split("\n")
        .map((s) => s.trim())
        .filter((s) => s.length > 0);
      await saveSettings();
      savedAt = new Date().toLocaleTimeString();
    } finally {
      saving = false;
    }
  }

  /** 当前选择对应的镜像地址（官方源 = null） */
  function selectedMirror(): string | null {
    if (mirrorKind === "custom") {
      const url = mirrorDraft.trim().replace(/\/+$/, "");
      return url === "" ? null : url;
    }
    return MIRRORS.find((m) => m.value === mirrorKind)?.url ?? null;
  }
</script>

<div class="page">
  <h2>设置</h2>

  <section class="card block">
    <span class="label">窗口背景</span>
    <select class="input" value={settings.windowEffect} onchange={onEffectChange}>
      {#each EFFECTS as e (e.value)}
        <option value={e.value}>{e.label}</option>
      {/each}
    </select>
    <p class="hint">若拖动窗口时明显掉帧，可切到「云母」或「不透明」。</p>
  </section>

  <section class="card block">
    <span class="label">游戏内存 (MB)</span>
    <input
      class="input"
      type="number"
      min="1024"
      max="16384"
      step="512"
      bind:value={settings.ramMb}
    />
  </section>

  <section class="card block">
    <span class="label">下载源</span>
    <select class="input" bind:value={mirrorKind}>
      {#each MIRRORS as m (m.value)}
        <option value={m.value}>{m.label}</option>
      {/each}
    </select>
    {#if mirrorKind === "custom"}
      <input
        class="input"
        type="text"
        placeholder="https://your-mirror.example.com"
        bind:value={mirrorDraft}
      />
      <p class="hint">留空则回退到官方源。地址需兼容 Mojang 的目录结构（/maven、/assets 子路径）。</p>
    {/if}
    <p class="hint">
      影响版本清单、依赖库、游戏资源与 Forge 的下载地址；国内网络建议选 BMCLAPI。
      Modrinth 与 GitHub 走自己的 CDN，不受此项影响。
    </p>
  </section>

  <section class="card block">
    <span class="label">额外 JVM 参数（每行一条）</span>
    <textarea
      class="input area"
      rows="3"
      placeholder={"-XX:+UseG1GC\n-Dsun.java2d.d3d=false"}
      bind:value={jvmDraft}
    ></textarea>
  </section>

  {#if paths}
    <section class="card block">
      <span class="label">数据目录</span>
      <code class="dir">{paths.root}</code>
      <button class="btn small" onclick={() => openTarget(paths!.root)}>打开目录</button>
    </section>

    <section class="card block">
      <div class="row">
        <span class="label">下载记录</span>
        <div class="row-actions">
          <button class="btn small" onclick={() => openTarget(paths!.manifestFile)}>
            打开清单
          </button>
          <button class="btn small" onclick={refreshManifest}>刷新</button>
        </div>
      </div>
      <p class="hint">
        客户端版本与模组的来源、sha1、安装时间都记在
        <code class="dir mono">launcher-manifest.json</code>，重装或校验时可直接对照。
      </p>

      {#if manifest}
        <div class="group">
          <span class="group-title">AirPlus 客户端（{manifest.clients.length}）</span>
          {#if manifest.clients.length === 0}
            <p class="hint">还没有下载过客户端。</p>
          {:else}
            <ul class="records">
              {#each manifest.clients as c (c.fileName)}
                <li>
                  <div class="line">
                    <span class="name" class:dim={!c.present}>{c.tag ?? "未知版本"}</span>
                    <span class="tag" class:on={c.active}>
                      {c.active ? "生效中" : c.present ? "已下载" : "已清理"}
                    </span>
                  </div>
                  <div class="meta">
                    {c.fileName} · {sizeText(c.size)} · sha1 {shortSha1(c.sha1)} · {dateText(c.installedAt)}
                  </div>
                </li>
              {/each}
            </ul>
          {/if}
        </div>

        <div class="group">
          <span class="group-title">模组（{manifest.mods.length}）</span>
          {#if manifest.mods.length === 0}
            <p class="hint">还没有安装过模组。</p>
          {:else}
            <ul class="records">
              {#each manifest.mods as m (m.fileName)}
                <li>
                  <div class="line">
                    <span class="name" class:dim={!m.present}>{m.fileName}</span>
                    <span class="tag">{m.source}</span>
                  </div>
                  <div class="meta">
                    {sizeText(m.size)} · sha1 {shortSha1(m.sha1)}
                    {#if m.versionNumber}· {m.versionNumber}{/if}
                    · {dateText(m.installedAt)}
                  </div>
                </li>
              {/each}
            </ul>
          {/if}
        </div>
      {/if}
    </section>
  {/if}

  {#if openError}
    <p class="error">{openError}</p>
  {/if}

  <div class="actions">
    <button class="btn primary" onclick={save} disabled={saving}>
      {saving ? "保存中…" : "保存设置"}
    </button>
    {#if savedAt}
      <span class="muted">已保存 {savedAt}</span>
    {/if}
  </div>
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    gap: 14px;
    max-width: 560px;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  .block {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 16px 18px;
  }
  .label {
    font-size: 13px;
    color: var(--text-muted);
  }
  .input {
    background: rgba(8, 8, 12, 0.45);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    color: var(--text);
    padding: 9px 12px;
    font-size: 13px;
    outline: none;
    width: 100%;
    box-sizing: border-box;
  }
  .input:focus {
    border-color: var(--accent);
  }
  .area {
    resize: vertical;
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 12px;
  }
  .dir {
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 12px;
    color: var(--text-muted);
    user-select: text;
    -webkit-user-select: text;
    word-break: break-all;
  }
  .hint {
    margin: 0;
    font-size: 12px;
    color: var(--text-muted);
  }
  .btn.small {
    padding: 6px 12px;
    font-size: 12px;
    align-self: flex-start;
  }
  .actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .muted {
    font-size: 12px;
    color: var(--text-muted);
  }
  .row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }
  .row-actions {
    display: flex;
    gap: 8px;
  }
  .group {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding-top: 4px;
    border-top: 1px solid var(--line);
  }
  .group-title {
    font-size: 12px;
    color: var(--text-muted);
    letter-spacing: 0.3px;
  }
  .records {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-height: 220px;
    overflow-y: auto;
  }
  .records li {
    background: rgba(8, 8, 12, 0.35);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 7px 10px;
  }
  .line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
  .name {
    font-size: 12.5px;
    font-weight: 600;
    word-break: break-all;
  }
  .name.dim {
    color: var(--text-muted);
    font-weight: 400;
  }
  .tag {
    flex: none;
    font-size: 11px;
    color: var(--text-muted);
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 1px 8px;
  }
  .tag.on {
    color: var(--ok);
    border-color: var(--ok);
  }
  .meta {
    margin-top: 3px;
    font-size: 11.5px;
    color: var(--text-muted);
    font-family: "Cascadia Mono", Consolas, monospace;
  }
  .mono {
    font-size: 11.5px;
  }
  .error {
    margin: 0;
    font-size: 12px;
    color: var(--danger);
    word-break: break-all;
    user-select: text;
    -webkit-user-select: text;
  }
</style>
