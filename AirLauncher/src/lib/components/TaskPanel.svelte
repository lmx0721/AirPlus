<script lang="ts">
  import { downloads, formatBytes } from "$lib/stores/downloads.svelte";
  import { downloadCancel } from "$lib/api/github";
</script>

<footer class="panel">
  {#if downloads.tasks.length === 0}
    <span class="muted">暂无进行中的任务</span>
  {:else}
    {#each downloads.tasks as task (task.taskId)}
      <div class="task">
        <div class="row">
          <span class="name" title={task.name}>{task.name}</span>
          {#if task.state === "running"}
            <span class="meta">
              {formatBytes(task.downloaded)}
              {#if task.total > 0}/ {formatBytes(task.total)}{/if}
              · {formatBytes(task.speedBps)}/s
            </span>
            <button class="cancel" onclick={() => downloadCancel(task.taskId)}>取消</button>
          {:else if task.state === "done"}
            <span class="meta ok">完成</span>
          {:else if task.state === "cancelled"}
            <span class="meta">已取消</span>
          {:else}
            <span class="meta err" title={task.error ?? ""}>失败</span>
          {/if}
        </div>
        <div class="bar">
          <div
            class="fill"
            class:err={task.state === "error"}
            class:ok={task.state === "done"}
            style="transform: scaleX({task.total > 0
              ? Math.min(task.downloaded / task.total, 1)
              : task.state === 'done'
                ? 1
                : 0})"
          ></div>
        </div>
      </div>
    {/each}
  {/if}
</footer>

<style>
  .panel {
    flex: none;
    max-height: 96px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 8px 16px;
    background: var(--window-strong);
    border-top: 1px solid var(--line);
    font-size: 12px;
  }
  .muted {
    color: var(--text-muted);
  }
  .task {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .name {
    max-width: 320px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .meta {
    color: var(--text-muted);
  }
  .meta.ok {
    color: var(--ok);
  }
  .meta.err {
    color: var(--danger);
  }
  .cancel {
    margin-left: auto;
    font-size: 11px;
    color: var(--text-muted);
    padding: 1px 8px;
    border-radius: 4px;
    border: 1px solid var(--line);
  }
  .cancel:hover {
    color: var(--danger);
    border-color: var(--danger);
  }
  .bar {
    height: 3px;
    border-radius: 2px;
    background: rgba(255, 255, 255, 0.08);
    overflow: hidden;
  }
  /*
   * 用 transform 而不是 width：width 变化会触发布局，且 100ms 一次的进度更新
   * 配上 0.15s 过渡永远追不上，等于无限重启合成动画。scaleX 只走合成层。
   */
  .fill {
    height: 100%;
    width: 100%;
    transform-origin: 0 50%;
    transform: scaleX(0);
    will-change: transform;
    background: var(--accent);
  }
  .fill.ok {
    background: var(--ok);
  }
  .fill.err {
    background: var(--danger);
  }
</style>
