<div align="center">

# AirPlus

**一个基于 Mixin 注入的 Minecraft 1.8.9 Forge 开源客户端**

**基于AirClient重构**

基于 [LiquidBounce Legacy](https://github.com/CCBlueX/LiquidBounce/tree/legacy) 二次开发，深度融合 Kotlin 现代化特性

![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9-8b89c4?logo=minecraft&logoColor=white)
![Forge](https://img.shields.io/badge/Forge-11.15.1.2318-56589c)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?logo=kotlin&logoColor=white)
![Modules](https://img.shields.io/badge/Modules-174+-3fb27f)
![License](https://img.shields.io/badge/License-GPL--3.0-e8b93e)

</div>

---

https://github.com/user-attachments/assets/b0afaeae-1c57-46d9-b6cc-f3860bc6cb17

## 简介

AirPlus 是在 LiquidBounce Legacy（b100 最终版）基础上构建的客户端，保留了原版成熟的 Mixin 注入架构与模块体系，并对多个模块进行了增强与重构。大部分代码移植自AirClient项目。


## 特性

- **模块化架构** — 事件驱动的模块系统，174+ 个功能模块，按 战斗 / 移动 / 渲染 / 玩家 / 世界 / 漏洞利用 / 杂项 分类管理
- **可编辑 HUD** — 所见即所得的 HUD 编辑器，自由拖拽、缩放组件
- **ClickGUI** — 现代化的图形设置界面，支持主题管理（ThemeManager）与颜色混合（ColorMixer）
- **脚本系统** — 内置 Kotlin/JS 脚本支持与 SRG Remapper，无需重新编译即可扩展功能
- **音乐播放器** — 内置 MusicPlayer 模块，游戏内直接播放音乐
- **Alt Manager** — 内置账号管理器，支持多种登录生成器
- **自定义披风** — CapeService 披风服务，告别官方披风限制


## 构建指南

### 环境要求

- JDK 8
- Gradle（推荐使用项目自带的 Gradle Wrapper）

### 步骤

```bash
# 1. 克隆仓库
git clone https://github.com/lmx0721/AirPlus.git
cd AirPlus

# 2. 构建发布 JAR
gradlew build
```

构建产物位于 `build/libs/` 目录下（Shadow 打包，已包含全部运行时依赖）。

### 开发环境搭建

1. 使用 IntelliJ IDEA 打开项目根目录，等待 Gradle 同步完成
2. 执行一次 `gradlew setupDecompWorkspace`（或在 Gradle 面板中运行对应任务）
3. 刷新 Gradle 项目，选择 Forge 的 `runClient` 运行配置即可启动调试客户端

### 项目结构

```
net.airplus
├── AirPlus.kt              # 客户端主入口
├── injection.forge         # Mixin 注入层（Forge CoreMod）
├── features
│   ├── module.modules      # 功能模块（combat / movement / render / ...）
│   └── command             # 命令系统
├── script                  # 脚本系统与 Remapper
├── ui.client               # ClickGUI / HUD / Alt Manager
├── utils                   # 渲染、移动、计时等工具类
└── file                    # 配置文件管理
```

## 技术栈

| 技术                                                     | 用途 |
|--------------------------------------------------------|------|
| [Mixin](https://github.com/SpongePowered/Mixin) 0.7.11 | 运行时字节码注入 |
| [Kotlin](https://kotlinlang.org) 2.0.21 + Coroutines   | 主要开发语言与异步调度 |
| Architectury Loom(以前为ForgeGradle)                      | 构建与开发环境 |
| [Shadow](https://github.com/johnrengelman/shadow)      | 依赖打包 |
| [DiscordIPC](https://github.com/jagrosh/DiscordIPC)    | Discord 状态展示 |
| [Elixir](https://github.com/CCBlueX/Elixir)            | CCBlueX 通用工具库 |
| FlatLaf                                                | Swing 界面主题 |

## 贡献

欢迎提交 Issue 与 Pull Request。修复反作弊兼容性、优化渲染性能、完善新模块都是非常好的切入点。

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

- 你可以自由地使用、分享和修改本项目，包括商业用途
- **你必须以相同的 GPL 协议开源你的修改版本，不得将本项目代码用于闭源或混淆的应用中**

## 致谢

- [CCBlueX / LiquidBounce](https://github.com/CCBlueX/LiquidBounce) — 本项目的基础
- 所有 LiquidBounce Legacy 社区维护者（@EclipsesDev、@mems01 等）
- SpongePowered 团队 — Mixin 框架
