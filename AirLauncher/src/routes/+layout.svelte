<script lang="ts">
  import "../app.css";
  import { onMount } from "svelte";
  import { view, type ViewId } from "$lib/stores/view.svelte";
  import { loadSettings } from "$lib/stores/settings.svelte";

  import TitleBar from "$lib/components/TitleBar.svelte";
  import Sidebar from "$lib/components/Sidebar.svelte";
  import TaskPanel from "$lib/components/TaskPanel.svelte";
  import HomePage from "$lib/pages/HomePage.svelte";
  import VersionsPage from "$lib/pages/VersionsPage.svelte";
  import ModsPage from "$lib/pages/ModsPage.svelte";
  import AccountsPage from "$lib/pages/AccountsPage.svelte";
  import SettingsPage from "$lib/pages/SettingsPage.svelte";
  import type { Component } from "svelte";
  import { initDownloadEvents } from "$lib/stores/downloads.svelte";

  /*
   * 页面常驻，不再用 {#key view.current} 整页销毁重建。
   * 之前每次切页都要卸载再挂载 HomePage，等于重新 listen 两个事件 + 清空日志状态，
   * 既丢数据又制造 IPC 抖动；现在只切换可见性，各页面自己处理「重新进入时刷新」。
   */
  const pageList: { id: ViewId; component: Component<{ active: boolean }> }[] = [
    { id: "home", component: HomePage },
    { id: "versions", component: VersionsPage },
    { id: "mods", component: ModsPage },
    { id: "accounts", component: AccountsPage },
    { id: "settings", component: SettingsPage },
  ];

  onMount(() => {
    void loadSettings();
    initDownloadEvents();
  });
</script>

<div class="shell">
  <TitleBar />
  <div class="content">
    <Sidebar />
    <main class="main">
      {#each pageList as p (p.id)}
        {@const Page = p.component}
        <div class="pane" class:active={view.current === p.id}>
          <Page active={view.current === p.id} />
        </div>
      {/each}
    </main>
  </div>
  <TaskPanel />
</div>

<style>
  /*
   * 亚克力可见层：半透明压暗 + 两层氛围光。
   * 这里不能再有不透明的 linear-gradient 底色，否则 DWM 模糊会被完全遮住。
   */
  .shell {
    display: flex;
    flex-direction: column;
    height: 100vh;
    background-color: var(--overlay);
    background-image:
      radial-gradient(1100px 560px at 12% -8%, rgba(7, 152, 252, 0.16), transparent 60%),
      radial-gradient(900px 520px at 108% 18%, rgba(124, 58, 237, 0.12), transparent 55%);
  }
  .content {
    flex: 1;
    display: flex;
    min-height: 0;
  }
  .main {
    position: relative;
    flex: 1;
    min-width: 0;
    overflow: hidden;
  }
  /*
   * 用 visibility 而不是 hidden/display:none：display:none 会把滚动容器的
   * scrollTop 重置为 0，切回去就跳回顶部。visibility:hidden 保留布局与滚动位置。
   */
  .pane {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    padding: 18px 22px 12px;
    visibility: hidden;
    pointer-events: none;
  }
  .pane.active {
    visibility: visible;
    pointer-events: auto;
  }
</style>
