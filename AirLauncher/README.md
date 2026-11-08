# AirLauncher

AirPlus 客户端（`lmx0721/AirPlus`）专属启动器 —— 亚克力半透明无边框窗口，深色毛玻璃 UI，与客户端 ThemeSelector 配色一脉相承。

> 技术栈：**Tauri 2（Rust 后端）+ SvelteKit 5 / TypeScript（前端）**

## 功能

| 功能 | 说明 |
|---|---|
| 🪟 无边框深色渐变窗口 | CSS 模拟毛玻璃氛围，无 DWM 合成依赖（规避 WebView2 透明窗口挂起问题），自绘标题栏 |
| 📦 客户端安装 | 拉取 GitHub Releases 版本列表 + 更新日志，一键下载 `AirPlus-*.jar` 并装入 mods |
| ☕ Java 8 自动下载 | 走 Adoptium API 下载 Temurin 8 JRE，解压到启动器目录，用户零配置 |
| ⚒️ Forge 1.8.9 自动安装 | 直接解析 Forge installer（不跑安装器），写 version JSON + universal jar |
| 🎮 完整启动游戏 | 版本链合并（Forge → 1.8.9）、libraries / natives / assets 下载校验、进程拉起、实时日志回传、可结束游戏进程树 |
| 👤 微软登录 | Rust 原生实现完整 OAuth → XBL → XSTS → Minecraft Services 流程，本地 http 回调，refresh token 静默续期，多账号管理 |
| 🧩 Modrinth 模组 | 搜索 / 按 `1.8.9 + forge` 过滤 / 版本列表 / 一键下载安装到实例 mods 目录 |
| ✨ OptiFine | 解析 optifine.net 两跳直链下载，失效时支持手动导入本地 jar |
| ⚙️ 其他 | 内存 / JVM 参数 / 下载镜像（BMCLAPI）/ GitHub PAT，统一下载管线（并发、断点续传、SHA1 校验、可取消） |

## 环境要求

- Node.js 20+ 与 pnpm
- Rust 1.77+（含 MSVC toolchain，Windows 需要 VS Build Tools + WebView2）

## 开发

```bash
# 安装前端依赖
pnpm install

# 开发模式（热重载窗口）
pnpm tauri dev

# 类型检查 + Rust 编译检查
pnpm check
cargo check --manifest-path src-tauri/Cargo.toml

# 打包（NSIS 安装包）
pnpm tauri build
```

打包产物位于 `src-tauri/target/release/bundle/nsis/`。

## 项目结构

```
AirLauncher/
├─ src/                        # 前端（SvelteKit 5 + TS）
│  ├─ lib/
│  │  ├─ api/                  # invoke 命令封装（app / github / launch / auth / mods）
│  │  ├─ components/           # TitleBar / Sidebar / TaskPanel
│  │  ├─ pages/                # Home / Versions / Mods / Accounts / Settings
│  │  ├─ stores/               # svelte 5 runes 状态（settings / accounts / downloads）
│  │  ├─ theme/tokens.css      # 客户端同款配色变量
│  │  └─ events.ts             # 后端事件监听（下载进度 / 启动日志 / 登录阶段）
│  └─ routes/+layout.svelte    # 单页 tab 骨架
├─ src-tauri/src/              # 后端（Rust）
│  ├─ auth/                    # 微软登录（microsoft 四步链 + callback 本地回调服务器）
│  ├─ download/                # 统一下载管线（并发 / 断点 / sha1 / 进度节流）
│  ├─ forge/                   # Forge installer 解析安装（免跑安装器）
│  ├─ github/                  # Releases 列表 / 更新日志 / asset 下载
│  ├─ java/                    # Adoptium Temurin 8 JRE 下载解压
│  ├─ launch/                  # 启动流程编排、命令行组装、进程管理
│  ├─ modrinth/                # Modrinth v2 API
│  ├─ mojang/                  # 版本元数据、inheritsFrom 合并、assets/libraries
│  ├─ optifine/                # optifine.net 直链解析 + 手动导入
│  ├─ config.rs / paths.rs     # 设置持久化、运行时目录派生（无硬编码绝对路径）
│  └─ net.rs / state.rs        # 共享 reqwest 客户端、取消令牌
└─ scripts/clean-build.mjs     # 打包前清理 build/（逐文件删除）
```

## 目录约定（运行时）

所有路径由 exe 位置 / 系统目录在运行时派生：

| 目录 | 用途 |
|---|---|
| `<exe目录>/instances/airplus-b4/` | 游戏实例（version JSON、libraries、assets、natives、mods） |
| `<exe目录>/jre/temurin8/` | 自动下载的 JRE 8 |
| `<exe目录>/cache/` | Forge installer 等下载缓存 |
| `%APPDATA%/net.airlauncher/` | settings.json、accounts.json |

## 相关链接

- AirPlus 客户端：https://github.com/lmx0721/AirPlus
- 上游启动逻辑参考：https://github.com/CCBlueX/LiquidLauncher
- 登录流程参考：https://github.com/RaphiMC/MinecraftAuth

## 许可

与 AirPlus 客户端一致，遵循 **GPL-3.0**。
