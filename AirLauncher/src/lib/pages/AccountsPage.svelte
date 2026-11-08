<script lang="ts">
  import { onMount } from "svelte";
  import { settings, saveSettings } from "$lib/stores/settings.svelte";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import {
    accounts,
    authState,
    initAuthListener,
    loadAccounts,
    removeAccount,
    startLogin,
  } from "$lib/stores/accounts.svelte";

  let { active = false } = $props();

  let copied = $state(false);

  onMount(() => {
    // 登录态事件监听只注册一次，页面现在常驻不会重复注册
    initAuthListener();
  });

  $effect(() => {
    if (active) void loadAccounts();
  });

  function selectAccount(id: string) {
    settings.activeAccountId = id;
    saveSettings();
  }

  async function copyCode() {
    try {
      await navigator.clipboard.writeText(authState.userCode);
      copied = true;
      setTimeout(() => (copied = false), 2000);
    } catch {
      // 剪贴板被拒时按钮没反应会让人困惑，这里让用户手动选中即可
      copied = false;
    }
  }

  function openVerify() {
    if (authState.verificationUri) {
      void openUrl(authState.verificationUri);
    }
  }
</script>

<div class="page">
  <section class="head">
    <div>
      <h2>账号</h2>
      <p class="muted">使用微软账号登录正版 Minecraft Java Edition</p>
    </div>
    <button
      class="btn"
      class:primary={authState.stage !== "waiting"}
      disabled={authState.busy && authState.stage !== "waiting"}
      onclick={startLogin}
    >
      {authState.busy
        ? "取消登录"
        : accounts.list.length === 0
          ? "登录微软账号"
          : "添加账号"}
    </button>
  </section>

  {#if authState.busy}
    <section class="card auth-progress">
      <div class="spinner"></div>
      <div class="progress-body">
        <strong>{authState.userCode ? "在浏览器中输入设备码" : "正在登录"}</strong>
        <p class="muted">{authState.message}</p>
        {#if authState.userCode}
          <div class="device-code">
            <code>{authState.userCode}</code>
            <div class="code-actions">
              <button class="btn small" onclick={copyCode}>
                {copied ? "已复制" : "复制代码"}
              </button>
              <button class="btn small" onclick={openVerify}>打开授权页</button>
            </div>
          </div>
          <p class="muted">代码约 15 分钟内有效；登录完成前请不要关闭启动器。</p>
        {/if}
      </div>
    </section>
  {:else if authState.stage === "error"}
    <section class="card auth-error">
      <p class="muted">上次登录失败：{authState.message}</p>
    </section>
  {/if}

  <section class="list">
    {#each accounts.list as acc (acc.id)}
      <div
        class="card account"
        class:active={settings.activeAccountId === acc.id}
        role="button"
        tabindex="0"
        onclick={() => selectAccount(acc.id)}
        onkeydown={(e) => e.key === "Enter" && selectAccount(acc.id)}
      >
        <div class="avatar">{acc.name.slice(0, 1).toUpperCase()}</div>
        <div class="info">
          <strong>{acc.name}</strong>
          <span class="muted mono">{acc.id}</span>
        </div>
        {#if settings.activeAccountId === acc.id}
          <span class="badge">当前</span>
        {/if}
        <button
          class="icon-btn"
          title="删除账号"
          onclick={(e) => {
            e.stopPropagation();
            removeAccount(acc.id);
          }}
        >
          ✕
        </button>
      </div>
    {/each}

    {#if accounts.list.length === 0 && !authState.busy}
      <div class="card empty muted">
        还没有账号。点击「登录微软账号」通过浏览器完成授权；
        也可以直接在首页用离线模式进入游戏。
      </div>
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
  }
  p {
    margin: 0;
  }
  .muted {
    color: var(--text-muted);
  }
  .mono {
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 11px;
  }
  .auth-progress {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 14px 16px;
  }
  .progress-body {
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 1;
    min-width: 0;
  }
  .device-code {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin: 4px 0 2px;
  }
  .device-code code {
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 22px;
    font-weight: 700;
    letter-spacing: 4px;
    color: var(--accent);
    background: rgba(7, 152, 252, 0.12);
    border: 1px solid rgba(7, 152, 252, 0.35);
    border-radius: var(--radius-sm);
    padding: 6px 14px;
    user-select: text;
    -webkit-user-select: text;
  }
  .code-actions {
    display: flex;
    gap: 8px;
  }
  .code-actions :global(.btn) {
    padding: 5px 12px;
    font-size: 12px;
  }
  .auth-error {
    padding: 12px 16px;
    border-color: rgba(220, 80, 80, 0.4);
  }
  .spinner {
    width: 18px;
    height: 18px;
    border: 2px solid var(--border);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .account {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    cursor: pointer;
  }
  .account.active {
    border-color: var(--accent);
  }
  .avatar {
    width: 34px;
    height: 34px;
    border-radius: 8px;
    background: var(--accent);
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    flex-shrink: 0;
  }
  .info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
  }
  .badge {
    font-size: 11px;
    color: var(--accent);
    border: 1px solid var(--accent);
    border-radius: 999px;
    padding: 2px 8px;
  }
  .icon-btn {
    background: transparent;
    border: none;
    color: var(--text-muted);
    cursor: pointer;
    font-size: 14px;
    padding: 4px 8px;
    border-radius: 6px;
  }
  .icon-btn:hover {
    color: #e06c6c;
    background: rgba(255, 255, 255, 0.06);
  }
  .empty {
    padding: 18px 16px;
    font-size: 13px;
  }
</style>
