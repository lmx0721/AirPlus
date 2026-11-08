<script lang="ts">
  import { onMount } from "svelte";
  import { pathsGet, type AppPathsInfo } from "$lib/api/app";
  import { launchStart, launchCancel, launchStatus } from "$lib/api/launch";
  import { accountsList, type AccountInfo } from "$lib/api/auth";
  import { clientStatus, type ClientStatus } from "$lib/api/client";
  import { onLaunchLog, onLaunchExit } from "$lib/events";
  import { settings, loadSettings } from "$lib/stores/settings.svelte";
  import ClientPicker from "$lib/components/ClientPicker.svelte";

  let { active = false } = $props();

  let paths = $state<AppPathsInfo | null>(null);
  let playerName = $state("Player");
  let msaAccount = $state<AccountInfo | null>(null);
  /** idle: 可启动 / preparing: 下载安装中 / running: 游戏运行中 */
  let phase = $state<"idle" | "preparing" | "running">("idle");
  /** mods 里的 AirPlus 客户端（null = 还没查过） */
  let client = $state<ClientStatus | null>(null);
  /** 版本选择弹窗 */
  let pickerOpen = $state(false);
  /** 弹窗是「启动触发的缺客户端」还是「主动切换版本」：前者下载完要接着启动 */
  let startAfterPick = $state(false);
  /** 日志用自增 id 做稳定 key，避免按索引 key 时整表重建 */
  let logs = $state<{ id: number; text: string }[]>([]);
  let logSeq = 0;
  const MAX_LOG_LINES = 150;
  /** 单行长度上限：游戏/模组偶尔会喷出几十万字符的长行，配上 word-break 会卡死布局 */
  const MAX_LINE_LEN = 400;

  function pushLogs(text: string) {
    // 后端日志按 100ms 合批发送，一次可能含多行
    for (const raw of text.split("\n")) {
      const line = raw.length > MAX_LINE_LEN ? `${raw.slice(0, MAX_LINE_LEN)}…` : raw;
      logs.push({ id: logSeq++, text: line });
    }
    if (logs.length > MAX_LOG_LINES) {
      logs.splice(0, logs.length - MAX_LOG_LINES);
    }
  }

  onMount(() => {
    // 监听是长期注册，只做一次；页面现在常驻，不会因为切页重复注册
    const unlisteners: Promise<() => void>[] = [
      onLaunchLog((line) => {
        pushLogs(line);
        if (line.includes("游戏已启动")) phase = "running";
      }),
      onLaunchExit(() => (phase = "idle")),
    ];
    return () => unlisteners.forEach((p) => p.then((fn) => fn()));
  });

  /** 每次切回首页时同步一次（其他页可能改了当前账号） */
  $effect(() => {
    if (!active) return;
    void syncStatus();
  });

  async function syncStatus() {
    pathsGet().then((p) => (paths = p));
    clientStatus()
      .then((c) => (client = c))
      .catch(() => {
        // 查不到就当未安装：点启动时会再查一次，真失败也有后端自动安装兜底
        client = { installed: false, fileName: null, tag: null };
      });
    try {
      const s = await launchStatus();
      phase = s.running ? "running" : "idle";
      // 必须等设置真正加载完，否则 activeAccountId 还是默认的 null
      await loadSettings();
      const id = settings.activeAccountId;
      if (!id) {
        msaAccount = null;
        return;
      }
      const list = await accountsList();
      msaAccount = list.find((a) => a.id === id) ?? null;
    } catch {
      /* 状态同步失败不影响界面可用性 */
    }
  }

  async function onLaunchButton() {
    if (phase === "preparing" || phase === "running") {
      launchCancel();
      return;
    }

    // 启动前必须确认 mods 里有客户端：没有的话进游戏就是原版 Forge 界面。
    // 查不到（后端异常）就交给 launch 内部的自动安装兜底，不阻断用户。
    try {
      const st = await clientStatus();
      client = st;
      if (!st.installed) {
        startAfterPick = true;
        pickerOpen = true;
        return;
      }
    } catch {
      /* 忽略，交给后端兜底 */
    }

    await doLaunch();
  }

  async function doLaunch() {
    logs = [];
    phase = "preparing";
    try {
      if (msaAccount) {
        await launchStart({ playerName: "", accountId: msaAccount.id });
      } else {
        const name = playerName.trim();
        if (!name) {
          phase = "idle";
          return;
        }
        await launchStart({ playerName: name, accountId: null });
      }
    } catch (e) {
      pushLogs(`启动失败: ${e}`);
      phase = "idle";
    }
  }

  function openPicker() {
    if (phase !== "idle") return;
    startAfterPick = false;
    pickerOpen = true;
  }

  /** 弹窗里下好并激活了客户端 */
  async function onClientReady() {
    clientStatus()
      .then((c) => (client = c))
      .catch(() => {});
    if (startAfterPick) {
      startAfterPick = false;
      await doLaunch();
    }
  }
</script>

<div class="page">
  <section class="hero card">
    <div>
      <h1>AirPlus</h1>
      <p class="sub">基于 LiquidBounce Legacy 的 Minecraft 1.8.9 Forge 客户端</p>
    </div>
    <div class="launch">
      {#if phase === "idle"}
        <input
          class="name"
          type="text"
          placeholder="玩家名"
          bind:value={playerName}
          maxlength="16"
        />
      {/if}
      <button
        class="btn primary"
        class:danger={phase === "running"}
        onclick={onLaunchButton}
      >
        {phase === "idle" ? "启动游戏" : phase === "preparing" ? "取消准备" : "结束游戏"}
      </button>
    </div>
  </section>

  <section class="grid">
    <button
      type="button"
      class="card stat clickable"
      onclick={openPicker}
      disabled={phase !== "idle"}
      title="点击选择要安装的客户端版本"
    >
      <span class="title">客户端版本</span>
      <span class="value">
        {client === null
          ? "检测中…"
          : client.installed
            ? `AirPlus ${client.tag ?? "已安装"}`
            : "未安装"}
      </span>
      <span class="hint">{client?.installed ? "点击切换版本" : "点击选择版本下载"}</span>
    </button>

    <div class="card stat">
      <span class="title">登录</span>
      <span class="value">{msaAccount ? msaAccount.name : "离线模式"}</span>
      <span class="hint">{msaAccount ? "微软正版账号" : "账号页可登录微软账号"}</span>
    </div>
  </section>

  <section class="card logbox">
    <span class="title">启动日志</span>
    <div class="logs">
      {#each logs as line (line.id)}
        <div>{line.text}</div>
      {/each}
      {#if logs.length === 0}
        <div class="empty">点击「启动游戏」开始，首次运行会先让你选一个客户端版本</div>
      {/if}
    </div>
  </section>

  {#if paths}
    <section class="card dir">
      <span class="title">数据目录</span>
      <code>{paths.root}</code>
    </section>
  {/if}
</div>

<ClientPicker
  open={pickerOpen}
  startAfterPick={startAfterPick}
  onclose={() => {
    pickerOpen = false;
    startAfterPick = false;
  }}
  onready={onClientReady}
/>

<style>
  .page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .hero {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 22px 24px;
  }
  .launch {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .name {
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--text);
    padding: 8px 12px;
    font-size: 13px;
    width: 140px;
    outline: none;
  }
  .name:focus {
    border-color: var(--accent);
  }
  h1 {
    margin: 0 0 4px;
    font-size: 26px;
    letter-spacing: 0.5px;
  }
  .sub {
    margin: 0;
    color: var(--text-muted);
    font-size: 13px;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
    gap: 12px;
  }
  .stat {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 14px 16px;
    text-align: left;
    font-family: inherit;
    color: var(--text);
    box-sizing: border-box;
  }
  /* 卡片里放 <button> 需要清掉浏览器默认外观 */
  button.stat {
    appearance: none;
    cursor: pointer;
    transition:
      background 0.15s,
      border-color 0.15s;
  }
  button.stat:hover:not(:disabled) {
    background: var(--card-hover);
    border-color: rgba(7, 152, 252, 0.4);
  }
  button.stat:disabled {
    cursor: default;
    opacity: 0.65;
  }
  .title {
    font-size: 12px;
    color: var(--text-muted);
  }
  .value {
    font-size: 15px;
    font-weight: 600;
  }
  .hint {
    font-size: 12px;
    color: var(--text-muted);
  }
  .logbox {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 14px 16px;
  }
  .logs {
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 12px;
    color: var(--text-muted);
    max-height: 220px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 2px;
    user-select: text;
    -webkit-user-select: text;
  }
  .logs :global(div) {
    white-space: pre-wrap;
    word-break: break-all;
  }
  .empty {
    opacity: 0.6;
  }
  .dir {
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  code {
    font-size: 12px;
    color: var(--text-muted);
    user-select: text;
    -webkit-user-select: text;
    word-break: break-all;
  }
</style>
