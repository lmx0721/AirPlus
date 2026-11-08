<script lang="ts">
  import {
    modrinthSearch,
    modrinthVersions,
    modrinthInstall,
    modsList,
    modDelete,
    optifineVersions,
    optifineInstall,
    modImportFile,
    type ModrinthHit,
    type ModrinthVersion,
    type OptifineVersion,
    type ModFile,
  } from "$lib/api/mods";
  import { open } from "@tauri-apps/plugin-dialog";

  // 搜索
  let query = $state("");
  let results = $state<ModrinthHit[]>([]);
  let searching = $state(false);
  let searchError = $state("");
  let expanded = $state<string | null>(null);
  let versions = $state<ModrinthVersion[]>([]);
  let versionsLoading = $state(false);

  // 已安装
  let installed = $state<ModFile[]>([]);

  // OptiFine
  let ofVersions = $state<OptifineVersion[]>([]);
  let ofLoading = $state(false);
  let ofError = $state("");

  let { active = false } = $props();

  async function refreshInstalled() {
    try {
      installed = await modsList();
    } catch {
      /* 列目录失败时保留上次结果 */
    }
  }

  $effect(() => {
    // 每次切回来都刷一遍：下载完成的 jar 可能刚落地
    if (active) void refreshInstalled();
  });

  async function doSearch() {
    if (!query.trim()) return;
    searching = true;
    searchError = "";
    expanded = null;
    try {
      results = await modrinthSearch(query.trim());
    } catch (e) {
      searchError = String(e);
      results = [];
    } finally {
      searching = false;
    }
  }

  async function toggleVersions(hit: ModrinthHit) {
    if (expanded === hit.projectId) {
      expanded = null;
      return;
    }
    expanded = hit.projectId;
    versionsLoading = true;
    try {
      versions = await modrinthVersions(hit.projectId);
    } finally {
      versionsLoading = false;
    }
  }

  async function install(version: ModrinthVersion) {
    await modrinthInstall(version);
    // 下载为后台任务，进度走任务面板；延时刷新列表
    setTimeout(refreshInstalled, 4000);
  }

  async function loadOptifine() {
    ofLoading = true;
    ofError = "";
    try {
      ofVersions = await optifineVersions();
    } catch (e) {
      ofError = String(e);
    } finally {
      ofLoading = false;
    }
  }

  async function importLocal() {
    const path = await open({
      multiple: false,
      filters: [{ name: "Mod jar", extensions: ["jar"] }],
    });
    if (!path) return;
    try {
      await modImportFile(path);
      await refreshInstalled();
    } catch (e) {
      ofError = String(e);
    }
  }

  function fmtSize(n: number): string {
    if (n > 1048576) return `${(n / 1048576).toFixed(1)} MB`;
    if (n > 1024) return `${(n / 1024).toFixed(0)} KB`;
    return `${n} B`;
  }
</script>

<div class="page">
  <!-- 搜索区 -->
  <section class="head">
    <div>
      <h2>模组</h2>
      <p class="muted">Modrinth · 自动过滤 Minecraft 1.8.9 + Forge</p>
    </div>
    <button class="import" onclick={importLocal}>导入本地 jar</button>
  </section>

  <form class="searchbar" onsubmit={(e) => { e.preventDefault(); doSearch(); }}>
    <input
      type="text"
      placeholder="搜索模组，如 Sodium、JourneyMap…"
      bind:value={query}
    />
    <button class="btn primary" type="submit" disabled={searching}>
      {searching ? "搜索中…" : "搜索"}
    </button>
  </form>

  {#if searchError}
    <div class="card err muted">搜索失败：{searchError}</div>
  {/if}

  <section class="results">
    {#each results as hit (hit.projectId)}
      <div class="card hit">
        <div
          class="hit-head"
          role="button"
          tabindex="0"
          onclick={() => toggleVersions(hit)}
          onkeydown={(e) => e.key === "Enter" && toggleVersions(hit)}
        >
          {#if hit.iconUrl}
            <img src={hit.iconUrl} alt="" class="icon" />
          {:else}
            <div class="icon placeholder">{hit.title.slice(0, 1)}</div>
          {/if}
          <div class="info">
            <strong>{hit.title}</strong>
            <span class="muted desc">{hit.description}</span>
            <span class="muted dl">{hit.downloads.toLocaleString()} 下载</span>
          </div>
          <span class="muted chev">{expanded === hit.projectId ? "▲" : "▼"}</span>
        </div>
        {#if expanded === hit.projectId}
          <div class="versions">
            {#if versionsLoading}
              <span class="muted">加载版本中…</span>
            {:else if versions.length === 0}
              <span class="muted">该模组没有适配 1.8.9 Forge 的版本</span>
            {:else}
              {#each versions as v (v.id)}
                <div class="ver">
                  <span class="mono">{v.versionNumber}</span>
                  <span class="muted">{fmtSize(v.fileSize)}</span>
                  <button class="btn small" onclick={() => install(v)}>安装</button>
                </div>
              {/each}
            {/if}
          </div>
        {/if}
      </div>
    {:else}
      {#if !searching && query}
        <div class="card empty muted">无结果</div>
      {/if}
    {/each}
  </section>

  <!-- OptiFine -->
  <section class="card of">
    <div class="head">
      <div>
        <h2>OptiFine</h2>
        <p class="muted">1.8.9 版本 · 放入 mods 目录由 Forge 加载</p>
      </div>
      <button class="btn" onclick={loadOptifine} disabled={ofLoading}>
        {ofLoading ? "获取中…" : "从官网获取版本"}
      </button>
    </div>
    {#if ofError}
      <p class="muted of-err">{ofError}</p>
    {/if}
    <div class="of-list">
      {#each ofVersions as v (v.fileName)}
        <div class="ver">
          <span>{v.display}</span>
          <button class="btn small" onclick={() => optifineInstall(v.fileName)}>
            安装
          </button>
        </div>
      {/each}
    </div>
  </section>

  <!-- 已安装 -->
  <section class="card installed">
    <h2>已安装（{installed.length}）</h2>
    {#if installed.length === 0}
      <p class="muted">mods 目录为空</p>
    {:else}
      {#each installed as m (m.fileName)}
        <div class="ver">
          <span class="mono">{m.fileName}</span>
          <span class="muted">{fmtSize(m.size)}</span>
          <button class="btn small danger" onclick={() => modDelete(m.fileName).then(refreshInstalled)}>
            删除
          </button>
        </div>
      {/each}
    {/if}
  </section>
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  h2 {
    margin: 0 0 4px;
    font-size: 18px;
  }
  p {
    margin: 0;
  }
  .muted {
    color: var(--text-muted);
    font-size: 12px;
  }
  .mono {
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 12px;
  }
  .import {
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px 14px;
    font-size: 13px;
    cursor: pointer;
  }
  .import:hover {
    border-color: var(--accent);
  }
  .searchbar {
    display: flex;
    gap: 8px;
  }
  .searchbar input {
    flex: 1;
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--text);
    padding: 9px 12px;
    font-size: 13px;
    outline: none;
  }
  .searchbar input:focus {
    border-color: var(--accent);
  }
  .err,
  .empty {
    padding: 12px 16px;
  }
  .results {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .hit-head {
    display: flex;
    align-items: center;
    gap: 12px;
    cursor: pointer;
  }
  .icon {
    width: 38px;
    height: 38px;
    border-radius: 8px;
    object-fit: cover;
    flex-shrink: 0;
  }
  .placeholder {
    background: var(--surface-2);
    color: var(--text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
  }
  .info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
  }
  .desc {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .dl {
    font-size: 11px;
  }
  .chev {
    font-size: 11px;
  }
  .versions,
  .of-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-top: 10px;
    padding-top: 10px;
    border-top: 1px solid var(--border);
  }
  .ver {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 4px 0;
  }
  .ver > span:first-child {
    flex: 1;
  }
  .of,
  .installed {
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .of-err {
    color: #e0a06c;
  }
  .btn.small {
    padding: 4px 12px;
    font-size: 12px;
  }
  .btn.danger {
    color: #e06c6c;
    border-color: rgba(220, 80, 80, 0.4);
  }
</style>
