<script lang="ts">
  import { view, type ViewId } from "$lib/stores/view.svelte";

  const items: { id: ViewId; label: string; path: string }[] = [
    {
      id: "home",
      label: "主页",
      path: "M3 10.5 12 3l9 7.5M5.5 9.5V20h13V9.5",
    },
    {
      id: "versions",
      label: "版本",
      path: "M12 3v10m0 0 4-4m-4 4-4-4M4 17v3h16v-3",
    },
    {
      id: "mods",
      label: "模组",
      path: "M12 2.5 21 7v10l-9 4.5L3 17V7l9-4.5Zm0 0V12m9-5-9 5m-9-5 9 5",
    },
    {
      id: "accounts",
      label: "账号",
      path: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-8 9a8 8 0 0 1 16 0",
    },
    {
      id: "settings",
      label: "设置",
      path: "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm8-3.5c0 .6-.06 1.17-.18 1.72l2.02 1.58-2 3.46-2.39-.96A8.2 8.2 0 0 1 14.4 19.2l-.36 2.55h-4l-.36-2.55a8.2 8.2 0 0 1-3.05-1.4l-2.4.96-2-3.46 2.03-1.58A8.6 8.6 0 0 1 4.06 12c0-.6.06-1.17.18-1.72L2.22 8.7l2-3.46 2.39.96A8.2 8.2 0 0 1 9.68 4.8l.36-2.55h4l.36 2.55a8.2 8.2 0 0 1 3.05 1.4l2.4-.96 2 3.46-2.03 1.58c.12.55.18 1.12.18 1.72Z",
    },
  ];
</script>

<nav class="sidebar">
  {#each items as item (item.id)}
    <button
      class="item"
      class:active={view.current === item.id}
      onclick={() => (view.current = item.id)}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path
          d={item.path}
          fill="none"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <span>{item.label}</span>
    </button>
  {/each}
</nav>

<style>
  .sidebar {
    width: 168px;
    flex: none;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 14px 10px;
    background: var(--window);
    border-right: 1px solid var(--line);
  }
  .item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 12px;
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: 13.5px;
    transition: background 0.12s, color 0.12s;
    text-align: left;
  }
  .item svg {
    width: 17px;
    height: 17px;
    flex: none;
  }
  .item:hover {
    background: rgba(255, 255, 255, 0.06);
    color: var(--text);
  }
  .item.active {
    background: var(--accent-soft);
    color: var(--accent);
  }
</style>
