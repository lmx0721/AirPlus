<script lang="ts">
  import { getCurrentWindow } from "@tauri-apps/api/window";

  const win = getCurrentWindow();

  function close() {
    void win.close();
  }
</script>

<!--
  拖动用声明式的 data-tauri-drag-region，不再手写 pointerdown/startDragging。

  原因：startDragging 会让主线程进入 Windows 的模态移动循环（WM_NCLBUTTONDOWN）。
  以前「位移超过 4px 才发异步 IPC」的写法存在竞态——手快甩一下就松手时，
  IPC 到达时鼠标键已经抬起，模态循环收不到配对的 WM_LBUTTONUP，窗口就黏在鼠标上假死。
  改用 data-tauri-drag-region 后由 Tauri 在 mousedown 的同步阶段直接调用，
  鼠标键必定还按着，不存在这个窗口期。

  注意语义：Tauri 只认「事件目标元素本身」带这个属性，子元素会吞掉事件。
  所以这里用一个铺满的透明 .drag-area 单独承接命中，品牌区靠 pointer-events:none 穿透给它，
  关闭按钮则靠更高的 z-index 抢回点击。
-->
<header class="titlebar">
  <div class="drag-area" data-tauri-drag-region></div>

  <div class="brand">
    <img class="logo" src="/airplus_icon.png" alt="" draggable="false" />
    <span class="name">AirLauncher</span>
    <span class="badge">for AirPlus</span>
  </div>

  <div class="controls">
    <button class="ctl close" aria-label="关闭" onclick={close}>
      <svg viewBox="0 0 12 12">
        <path d="M1.5 1.5 10.5 10.5 M10.5 1.5 1.5 10.5" stroke="currentColor" stroke-width="1.2" />
      </svg>
    </button>
  </div>
</header>

<style>
  .titlebar {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 42px;
    flex: none;
    padding-left: 14px;
    background: var(--window-strong);
    border-bottom: 1px solid var(--line);
    user-select: none;
    cursor: default;
  }
  /* 铺满整条标题栏，只负责命中，不做任何视觉表现 */
  .drag-area {
    position: absolute;
    inset: 0;
    z-index: 0;
  }
  /* 品牌区不参与命中 → 事件穿透到 .drag-area，在 Logo/文字上也能拖 */
  .brand {
    position: relative;
    z-index: 1;
    pointer-events: none;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .logo {
    width: 18px;
    height: 18px;
    border-radius: 4px;
    /* 图标是蓝色圆角方块，和深色标题栏对比够，不需要额外描边 */
    display: block;
  }
  .name {
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.3px;
  }
  .badge {
    font-size: 11px;
    color: var(--text-muted);
  }
  .controls {
    position: relative;
    z-index: 2;
    pointer-events: auto;
    display: flex;
    height: 100%;
  }
  .ctl {
    width: 46px;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-muted);
    transition: background 0.12s, color 0.12s;
  }
  .ctl svg {
    width: 11px;
    height: 11px;
  }
  .ctl:hover {
    background: rgba(255, 255, 255, 0.08);
    color: var(--text);
  }
  .ctl.close:hover {
    background: var(--danger);
    color: #fff;
  }
</style>
