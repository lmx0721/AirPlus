<script lang="ts">
  import { listReleases, downloadAsset, type GhRelease, type GhAsset } from "$lib/api/github";
  import { downloads, formatBytes } from "$lib/stores/downloads.svelte";
  import { marked } from "marked";
  import DOMPurify from "dompurify";

  let releases = $state<GhRelease[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let selectedTag = $state<string | null>(null);

  let { active = false } = $props();
  let lastLoaded = 0;
  /** GitHub 未认证接口只有 60 次/小时，切页回来不要重复拉 */
  const CACHE_MS = 5 * 60 * 1000;

  const jarAssetOf = (r: GhRelease): GhAsset | undefined =>
    r.assets.find((a) => a.name.endsWith(".jar") && !a.name.includes("sources"));

  const taskOf = (assetName: string) =>
    downloads.tasks.find((t) => t.name === assetName);

  const renderedBody = $derived.by(() => {
    const r = releases.find((x) => x.tag_name === selectedTag);
    if (!r?.body) return "";
    return DOMPurify.sanitize(marked.parse(r.body, { async: false }) as string);
  });

  const formatDate = (iso: string | null) =>
    iso ? new Date(iso).toLocaleDateString("zh-CN") : "";

  async function load() {
    loading = true;
    loadError = null;
    try {
      releases = await listReleases();
      lastLoaded = Date.now();
      if (releases.length > 0) selectedTag = releases[0].tag_name;
    } catch (e) {
      loadError = String(e);
    } finally {
      loading = false;
    }
  }

  function install(asset: GhAsset, tag: string) {
    void downloadAsset(asset, tag);
  }

  $effect(() => {
    if (!active) return;
    if (releases.length > 0 && Date.now() - lastLoaded < CACHE_MS) return;
    void load();
  });
</script>

<div class="page">
  {#if loading}
    <p class="muted">正在加载版本列表…</p>
  {:else if loadError}
    <div class="error card">
      <p>加载版本列表失败：{loadError}</p>
      <button class="btn" onclick={load}>重试</button>
    </div>
  {:else}
    <div class="split">
      <aside class="list">
        {#each releases as r (r.tag_name)}
          <button
            class="rel"
            class:active={selectedTag === r.tag_name}
            onclick={() => (selectedTag = r.tag_name)}
          >
            <span class="tag">
              {r.tag_name}
              {#if r.prerelease}<em class="pre">预发布</em>{/if}
            </span>
            <span class="date">{formatDate(r.published_at)}</span>
          </button>
        {/each}
      </aside>

      <section class="detail card">
        {#each releases.filter((r) => r.tag_name === selectedTag) as r (r.tag_name)}
          <h2>{r.name ?? r.tag_name}</h2>

          <div class="assets">
            {#each r.assets as asset (asset.id)}
              {@const task = taskOf(asset.name)}
              <div class="asset">
                <span class="aname" title={asset.name}>{asset.name}</span>
                <span class="asize">{formatBytes(asset.size)}</span>
                {#if task?.state === "running"}
                  <span class="dlmeta">{formatBytes(task.downloaded)} / {formatBytes(task.total)}</span>
                {:else}
                  <button class="btn" onclick={() => install(asset, r.tag_name)}>下载</button>
                {/if}
              </div>
            {/each}
          </div>

          <div class="changelog">
            <h3>更新日志</h3>
            {@html renderedBody}
          </div>
        {/each}
      </section>
    </div>
  {/if}
</div>

<style>
  .page {
    height: 100%;
    display: flex;
    flex-direction: column;
  }
  .muted {
    color: var(--text-muted);
  }
  .error {
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
  }
  .split {
    flex: 1;
    display: flex;
    gap: 14px;
    min-height: 0;
  }
  .list {
    width: 200px;
    flex: none;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding-right: 2px;
  }
  .rel {
    display: flex;
    flex-direction: column;
    gap: 2px;
    text-align: left;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--line);
    background: rgba(8, 8, 12, 0.3);
    color: var(--text);
  }
  .rel:hover {
    background: var(--card-hover);
  }
  .rel.active {
    border-color: rgba(7, 152, 252, 0.5);
    background: var(--accent-soft);
  }
  .tag {
    font-size: 13px;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .pre {
    font-style: normal;
    font-size: 10px;
    color: var(--accent);
    border: 1px solid rgba(7, 152, 252, 0.4);
    border-radius: 4px;
    padding: 0 5px;
  }
  .date {
    font-size: 11px;
    color: var(--text-muted);
  }
  .detail {
    flex: 1;
    min-width: 0;
    overflow-y: auto;
    padding: 20px 22px;
  }
  h2 {
    margin: 0 0 14px;
    font-size: 18px;
  }
  h3 {
    margin: 18px 0 8px;
    font-size: 14px;
    color: var(--text-muted);
  }
  .assets {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .asset {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 12px;
    border-radius: var(--radius-sm);
    background: rgba(8, 8, 12, 0.35);
    border: 1px solid var(--line);
  }
  .aname {
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .asize {
    font-size: 11px;
    color: var(--text-muted);
    flex: none;
  }
  .dlmeta {
    margin-left: auto;
    font-size: 11px;
    color: var(--accent);
  }
  .asset .btn {
    margin-left: auto;
    padding: 4px 14px;
  }
  .changelog {
    user-select: text;
    -webkit-user-select: text;
    font-size: 13px;
    line-height: 1.6;
  }
  .changelog :global(code) {
    background: rgba(8, 8, 12, 0.5);
    padding: 1px 5px;
    border-radius: 4px;
    font-size: 12px;
  }
  .changelog :global(pre) {
    background: rgba(8, 8, 12, 0.5);
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    overflow-x: auto;
  }
  .changelog :global(a) {
    color: var(--accent);
  }
  .changelog :global(img) {
    max-width: 100%;
  }
</style>
