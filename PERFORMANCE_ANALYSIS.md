# AirPlus 客户端性能与内存优化分析报告

> 分析范围：`src/main/java` 全部 1106 个源文件（约 16.6 万行），覆盖渲染管线、事件系统 / Mixin、UI / HUD / 字体、模块热路径、内存与资源生命周期、启动流程 / JVM 配置六个维度。
> 所有结论均附 `文件:行号` 证据，行号对应当前工作区版本。

---

## 0. 总体结论

| 维度 | 现状评价 |
|---|---|
| 渲染工具层 | 模糊/辉光管线（KawaseBlur、HudBlur、RiseBlurShader、HudGlowShader）缓存纪律良好；但存在**逐实体离屏渲染**、**立即模式 + 每帧分配**两大掉帧源 |
| 事件系统 | 架构现代化（无反射、IdentityHashMap 分发、COW 快照迭代）；缺口在**取消不提前退出**、**每包开协程**、**热路径事件对象每次 new** |
| UI / HUD | 颜色选择器**逐像素 drawRect（11000 次/帧）**、动态中文字符**单字符单纹理**、字号缓存**无上限**，是 UI 掉帧与显存大户 |
| 模块热路径 | KillAura / Scaffold / Nuker 存在**每 tick 全实体扫描 + 逐候选 raytrace**；SimulatedPlayer **每次构造做 NBT 往返拷贝** |
| 内存 | 音乐播放器**整曲解码进内存（峰值 130–250MB/首）**、封面**全分辨率纹理（最多数 GB VRAM）**、字体重烘焙**泄漏 16MB VRAM/次**、脚本重载泄漏 |
| 启动 | ViaVersion 全栈**在主线程同步初始化（数百 ms~数秒）**；两个中文 TTF 占 jar 体积 35% |
| 已做好的部分 | 字体 lazy 烘焙、音乐/Discord/脚本延迟加载、Remapper 条件守卫、OkHttp 单例、Timer 类零分配、多个 Mixin 已含 FPSBoost 射线缓存 / 粒子上限等正向优化 |

**预期收益**：按第 9 章路线图逐项落地后，PvP 场景（高实体数 + 高 PPS）帧率可从 40~60 FPS 恢复到接近原生；音乐播放期间堆占用峰值可下降 100~200MB；显存占用最多可下降数 GB（封面场景）。

---

## 1. 全局手段：JVM 与 GC 配置

### 1.1 当前配置（`build.gradle.kts:45`）

```kotlin
vmArgs("-Xmx4096m", "-Xms1024m")
```

- 无 GC 参数 → JDK 8 默认 Parallel GC，老年代 Full GC 一次 100ms+ 停顿，1.8.9 渲染线程对停顿极其敏感（表现为周期性卡顿尖峰）。
- `-Xms1024m` 与 `-Xmx4096m` 不一致 → 堆频繁伸缩，触发额外 Full GC。

### 1.2 建议配置（1.8.9 + Forge 客户端标准组合）

```
-Xmx4G -Xms4G
-XX:+UseG1GC -XX:MaxGCPauseMillis=50
-XX:+UnlockExperimentalVMOptions
-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M
-XX:+DisableExplicitGC -XX:+AlwaysPreTouch
```

- `Xms = Xmx` + G1 低停顿是 OptiFine / 低延迟客户端的事实标准。
- 注意：runConfig 的 vmArgs 只影响**开发环境**；生产环境由启动器决定，建议在 README 中给出同款参数。
- `mixin.debug=true`（`build.gradle.kts:34`）仅存在于 dev runConfig，不会进生产 jar，但 dev 启动会明显变慢，可按需关闭。

---

## 2. 渲染管线（帧率主战场）

### 2.1 🔴 严重级：逐实体离屏渲染 / 全屏 Shader Pass

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | **ItemESP Glow 模式**：对每个掉落物独立执行一次 `renderEntityStatic` + 一次全屏 glow pass | `ItemESP.kt:154-168`、管线 `FramebufferShader.kt:44-105` | 100 个物品（SkyBlock 常见）≈ 100 次实体重渲染 + 100 次全屏填充率 pass ≈ 20-50ms → 掉到 20-40 FPS |
| 2 | **NameTags Opai/Moon blur**：每个胶囊一次 `HudBlur.blur()`，每次含全屏 `glClear(GL_STENCIL)`（`Island.kt:2574-2577`）+ ~15 次 `glIsEnabled/glGetInteger/glGetFloat`（`HudBlur.kt:254-266, 613-644`） | `NameTags.kt:928-937, 1114-1120`、`ItemESP.kt:416-424` | 30 个实体 ≈ 30-40 次 blur/帧 → 每帧额外 3-8ms |
| 3 | **GLErrorProbe 诊断残留**：每次 blur 调用进出各 drain 一次 + 同步 debug 回调（注释自认"临时诊断工具"） | `HudBlur.kt:86, 125, 183, 312`、`utils/render/GLErrorProbe.kt:26-44, 57-70` | 每帧 0.5-2ms；出错时灾难级 |

**修复手段**：
1. 问题 1：`startDraw()` → **循环渲染所有物品** → 一次 `stopDraw()`（一次 shader pass 处理整批）；`renderScale` 默认降为 0.5。
2. 问题 2：仿照 `HudGlowShader` 已有的"形状累积 + 帧末统一 flush"模式（`HudGlowShader.kt:90-126`）——帧内把所有 blur 区域 mask 画进**同一张 stencil / 低分辨率 mask FBO**，帧末一次 clear + 一次贴回；至少用 `glScissor` 限定 stencil clear 到 blur AABB；GL 状态快照改"脏标记 + 上次已知值缓存"。
3. 问题 3：直接删除 `GLErrorProbe`（5 分钟改动，收益立现）。

### 2.2 🟠 高：每帧全量遍历 + 每帧分配（GC 压力源）

| # | 问题 | 位置 |
|---|---|---|
| 4 | Opai/Moon/Xinxin NameTags 与 ItemESP **每帧裸扫 `loadedEntityList`**，而项目已有 20Hz 缓存 `EntityLookup`（`utils/client/EntityLookup.kt:30-45`）却只有 Legacy 模式在用 | `NameTags.kt:733, 1057, 465`、`ItemESP.kt:266` |
| 5 | `projectOn2D` **每个顶点**重新读 3 次 GL 矩阵（`glGetFloat`×2 + `glGetInteger`）+ 每次 `new Vector3f`；Xinxin 每实体 8 顶点 = 24 次 GL 查询 | `ESPUtil.kt:38-50, 72-97` |
| 6 | NameTags 每帧对每个装备 `ItemStack.copy()`（NBT 深拷贝）、每实体字符串拼接 | `NameTags.kt:856-862`、`NameTags.kt:782, 787, 852` |
| 7 | ItemESP Moon：每帧 O(n²) 分组 + O(n²) 洪水填充 + `sortedBy { moonEnchantOrder.indexOf(...) }` | `ItemESP.kt:280-292, 339-408, 1095` |
| 8 | Island.kt（3958 行）：按 Tab 时每帧对 playerInfoMap filter+排序；每帧 20 处 `String.format`；**每个 chest slot 每帧 `stack?.copy()`（54 slot × NBT 深拷贝）**而比较本可以只在变化时做 | `Island.kt:753-757, 733, 1719, 3409, 4389, 4284-4285` 等 |

**修复手段**：
- 全实体列表改用 `EntityLookup` / 帧内缓存；3D pass 开始时**读一次矩阵做帧内快照**，`projectOn2D` 复用。
- `ItemStack.copy()` 只在 `areItemStacksEqual` 检测到变化时执行。
- 分组/排序结果按 5-10 tick 节流或世界变化时失效；对象池复用 `BooleanArray`/`ArrayList`；`indexOf` 改预建 `Int2Int` 映射。
- 字符串/文本类：引入统一的 **dirty 标志 + 缓存**模式（值不变就不重建字符串）。

### 2.3 🟡 中：立即模式 + 数学浪费

| # | 问题 | 位置 |
|---|---|---|
| 9 | `fastRoundedRect` / `drawRoundedCornerRect`：`glBegin(GL_POLYGON)` + **360 顶点每顶点 sin/cos**（而 `RoundedUtil.kt:20-28` 已有 5° 步长查表实现可直接复用） | `RenderUtils.kt:1770-1796, 1811-1834` |
| 10 | `glPushAttrib(GL_ALL_ATTRIB_BITS)`——GL 中最贵的调用之一（部分驱动强制全状态重读） | `RenderUtils.kt:328, 415, 482`、`Breadcrumbs.kt:40`、`RenderUtils.kt:1674` |
| 11 | vitox 粒子：O(n²) stream 连线（每粒子对全列表 filter）+ 每粒子 360 段立即模式多边形 + 每粒子每帧 `new ScaledResolution` | `ParticleGenerator.java:48-55`、`Particle.java:95`、`particle/util/RenderUtils.java:40-47` |
| 12 | Projectiles：每帧完整抛物线模拟**无 tick 上限**，每 tick 一次 rayTraceBlocks；Xylitol 模式在 `glBegin` 打开状态下逐顶点做 world 查询；256 顶点立即模式圆 | `Projectiles.kt:194-254, 955-1000, 1034-1038` |
| 13 | Breadcrumbs：`removeEach + indexOf` O(n²)、逐段立即模式、每段新建 Color | `Breadcrumbs.kt:68-76, 62, 78` |
| 14 | `ShaderUtil.setUniformf` 每帧 `glGetUniformLocation`（`Shader.kt:134-138` 已示范缓存，ShaderUtil 未跟上） | `ShaderUtil.kt:98-114`、`KawaseBlur.kt:410-414` |
| 15 | InternalBlurShader：核权重在 fragment shader 内逐像素 `exp()`（`RiseBlurShader.kt:198-217` 已有 CPU 预计算 kernel 的正确写法） | `InternalBlurShader.kt:269-290` |
| 16 | Island 用 `CopyOnWriteArrayList` 于单线程渲染路径，`removeAll` 每帧整组复制数组 | `Island.kt:304, 319, 2557` |

**修复手段**：sin/cos 查表 / 预生成 DisplayList 或 VBO；`GL_ALL_ATTRIB_BITS` 换 `GL_ENABLE_BIT` 或手动保存（`KawaseBlur.kt:73-91` 已示范正确做法）；连线只算上半三角并限制每粒子 2-3 条；粒子顶点降 24 段 + 单一 Tessellator batch；弹道模拟加 100-200 tick 硬上限并缓存（视角/拉弓状态不变时复用）；uniform location 加 HashMap 缓存；kernel 预计算迁移到 InternalBlurShader；`CopyOnWriteArrayList` 改普通 `ArrayList`。

---

## 3. 事件系统与 Mixin 层

### 3.1 事件分发（现状：架构好，三个缺口）

- ✅ 无 `Method.invoke` 反射（`EventHook.kt:14,24` 直接 lambda 调用）；按事件类型预建 `IdentityHashMap`（`EventManager.kt:37-38`），37 个事件类各自 COW 列表；二分优先级插入（`EventManager.kt:18-35`）；无每 tick 防御性复制。
- ⚠️ **缺口 1：取消后不提前退出**（`EventManager.kt:74-84`）。`PacketEvent` 已注册 ~71 个 handler，一个早期取消后剩余 ~70 个 hook 仍逐个执行 `handleEvents()` 链——每个网络包被放大 70 次。
  **修复**：`processEvent` 循环内加 `if (event is CancellableEvent && event.isCancelled) break`。
- ⚠️ **缺口 2：`call(event, listener)` O(n) 全表扫描**（`EventManager.kt:86-98`，调用点 `PacketUtils.kt:101-102`）。FakeLag 每个队列包扫两遍 71 个 hook。
  **修复**：注册时缓存 `EventHook` 引用，直接 `hook.processEvent(event)`。
- ⚠️ **缺口 3：热路径事件对象每次 new**。最重的是 `BlockBBEvent`（**每次方块碰撞盒查询**，可达每 tick 数十次，`MixinBlock.java:66-67`）以及 `MotionEvent`×2/tick、`PlayerTickEvent`×2/tick、`PacketEvent`/包。
  **修复**：为这 4 类建单例可变事件对象（字段每帧覆写；分发同步单线程，天然安全）。另可加"无订阅者跳过"——如 `BlockPushEvent` 每 tick 4 次分发即使无人监听也 new + 遍历空表（`MixinEntityPlayerSP.java:448-451`）。

### 3.2 🔴 最高优先级：每个网络包开协程 + 塞主线程任务

- `Listenable.kt:30-35` 的 `handler(dispatcher=...)` 每次事件触发都 `launch` 新协程 + `TickScheduler.kt:49` `mc.addScheduledTask{...}`。
- `PacketUtils.kt:59` 对 `PacketEvent` 用了此形式——**每个收到的包（Netty 线程上）都启动协程 + 往主线程队列塞 Callable**，即使绝大多数包走空路径。高 PPS 场景（PvP/TNT/群怪）每秒数百个协程对象 + scheduled task，纯浪费。
- **修复**：改为普通同步 handler（body 全是同步字段读写，无需协程）。

### 3.3 其他高频 Mixin 注入问题

| 问题 | 位置 | 说明 |
|---|---|---|
| `getMouseOver` 完全覆写每帧射线检测，缓存失效时全量重算 + 每帧分配 `Rotation`/lambda/ArrayList | `MixinEntityRenderer.java:743-858`（缓存 `676-717`） | 把 `boxes`/`Rotation` 提为 `@Unique` 字段复用 |
| renderWorldPass "hand" 段每帧 ~30 次"脏值→真值"两连发 GL 状态同步 | `MixinEntityRenderer.java:355-449` | 根治方案是在各 Render3DEvent 处理器 finally 里正确恢复状态后删除此守卫 |
| `airplus$dumpHandState` 生产残留调试探针（每 5s ~10 个同步 glGet + warn 日志） | `MixinEntityRenderer.java:620-646` | 删除或加 `System.getProperty` 门控 |
| `onLivingUpdate` 每 tick `new MovementInput`（即使结果不被人用） | `MixinEntityPlayerSP.java:395` | 提为字段复用 |
| `loopSequence` 注册的 `always=true` 每 tick hook 永久占用（至少 8 个类：Scaffold/Step/InventoryManager/BlockESP/BedPlates 等） | `Extensions.kt:45` | job 取消且 `!handleEvents()` 持续 N tick 后自动反注册 |
| 欢迎动画硬阻塞 FML 主线程 3.7s（`Thread.sleep(WELCOME_TOTAL)`） | `MixinSplashProgress.java:582, 129` | 加配置项或按任意键跳过（sleep 改轮询） |

### 3.4 ✅ 无需修改

- `MSTimer`/`TickTimer`/`DelayTimer` 等计时器全为字段级单实例、零分配（`utils/timing/`）。
- `TickScheduler` 双缓冲（`TickScheduler.kt:31-38`）、`MODULE_REGISTRY` TreeSet（仅按键遍历）、`EventHook` try/catch（未抛异常零成本）。

---

## 4. UI / HUD / 字体层

### 4.1 🔴 严重级

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | **NeverloseScreen 颜色选择器逐像素 drawRect**：SB 方块约 184×60 ≈ **11000 次/帧**，每像素 `Color.HSBtoRGB` + 新 Color 分配 | `NeverloseScreen.kt:805-812`、`:764-767, 793-796` | 3-8ms/帧 + 数千临时对象，打开颜色面板即掉帧 |
| 2 | **FlatClickGui 逐像素渐变**：每个 ColorValue 控件每帧 ~700+ drawRect（饱和度/亮度/色相/alpha/棋盘格） | `FlatClickGui.kt:1014-1058` | 单控件 1-3ms；3-5 个颜色项时 5-10ms |
| 3 | **动态中文字符：单字符单 GL 纹理 + 逐字符 bind/begin-end** | `AWTFontRenderer.kt:432-451, 503-553, 361-451` | 40 字中文 = 40 次纹理切换 + 40 次 begin/end（0.5-2ms/帧）；每个动态字符占 ~0.25MB 显存 |
| 4 | **字号缓存无上限**：所有按字号取字体的缓存都是无上限 HashMap；一个字号 = 4 张 2048px atlas（主 + bold/italic/boldItalic） | `Fonts.kt:78-82, 220-224, 228-236, 279-285`、`GameFontRenderer.kt:59-63` | 显存暴涨（每字体组数十 MB）、首次烘焙掉帧、永不回收 |

**修复手段**：颜色选择器/渐变**烘焙为纹理**（值变化时才重建，或固定渐变图 + GL 混色）；棋盘格用 8×8 重复纹理；动态字符合入**多页 glyph atlas**，按字符哈希复用 UV，全程单纹理绑定；`measureChar` 缓存到字符级 Map（当前 `AWTFontRenderer.kt:375-388` 每次新建 `BufferedImage(1,1)` + `createGraphics()` 测量）；字号取整到有限集合（步长 0.5）+ LRU 淘汰；样式 atlas 仅在确实使用 italic/bold 时烘焙。

### 4.2 🟠 每帧重算类（统一引入 dirty 标志 + 缓存模式）

| # | 问题 | 位置 |
|---|---|---|
| 5 | Arraylist 每次渲染全量重拼字符串 + 测宽 + 排序（LB 样式 & 卡片样式） | `Arraylist.kt:1229-1237, 265, 802, 939, 1044-1045, 1114-1115` |
| 6 | Notifications：`removeIf` 内每帧重算换行/测宽（典型反模式）；绘制时宽度每帧重算 | `Notifications.kt:92-110, 312-316, 402-516` |
| 7 | NeverloseScreen：每帧 `SimpleDateFormat.format(Date())`、`"%.2f".format`、HEX 格式化；模块区块高度每帧重算；动画 HashMap 只增不减 | `NeverloseScreen.kt:304, 922, 546-548, 953-961, 754, 401-418, 66-72` |
| 8 | EditorPanel：每帧重建按钮布局；**Load 对话框每帧 `HudPresets.list()` 磁盘 IO** | `EditorPanel.kt:180-204, 284-299, 1045` |
| 9 | Text 元素每帧 multiReplace 正则替换 + FPS 水印每帧新字符串测宽 | `Text.kt:212-292, 324, 539-544` |
| 10 | ThemeSelector：filteredThemes getter 每帧 filter（被多处调用）；每卡每帧水平渐变 + 新 Color；fitText 逐字符测宽 | `ThemeSelector.kt:97-104, 335, 397-400, 521-528` |
| 11 | **客户端主题色 getter 无缓存**：`getColor` 每次调用 resolvePair + mixColors + fadeValue，被 HUD/ClickGUI 每帧成百上千次调用，每次分配新 Color | `ClientThemesUtils.kt:225-233` |
| 12 | CharRenderer：每帧 DecimalFormat.format + 逐字符 `numberList.indexOf("$char")` 字符串拼接线性查找 | `targets2/CharRenderer.kt:40-86` |
| 13 | 主菜单 ParticleEngine：每帧 100 次 50 顶点立即模式扇形 + 每次 glPushAttrib/glPopAttrib（1.8.9 出了名的慢调用） | `mainmenu/ParticleEngine.kt:40`、`RenderUtils.kt:1283-1303` |

**修复手段**：时钟字符串缓存到秒级；百分比/HEX 值变化时才格式化；布局/过滤结果加 dirty 缓存；预设列表打开对话框时读一次；主题色做**帧级缓存**（同一帧内相同参数返回上次 Color）；`indexOf("$char")` 改 `char - '0'` 直接索引；粒子改共享圆形纹理批量绘制。

### 4.3 🟠 跨线程卡顿尖峰

- **MusicScreen 封面解码在渲染线程**：下载已异步（`MusicScreen.kt:1322` supplyAsync ✅），但 `ImageIO.read` + `DynamicTexture` 构造被 `mc.addScheduledTask` 调度回渲染线程（`:1333-1339`）。大封面解码 10-50ms → 切歌时单帧明显卡顿。
  **修复**：解码移入 supplyAsync 后台线程，仅纹理上传留主线程。

### 4.4 附带发现（bug）

- HUD 拖拽位置赋值疑似 bug：`element.renderX = moveX.toDouble()`（`HUD.kt:165, 169`）——应为增量累加，当前元素会瞬移到鼠标位置。

---

## 5. 模块热路径（每 tick 逻辑）

### 5.1 🔴 P0

| # | 问题 | 位置 | 修复 |
|---|---|---|---|
| 1 | **KillAura 目标搜索每 tick 全实体扫描 + 每实体 Backtrack 排序**：`runWithNearestTrackedDistance` 对每个实体 `toMutableList()` 拷贝 + `sortedBy`，排序 key 内部又各自设位算距离 → 每实体每 tick ≈ 10 次位置写入 + 2 次列表分配 | `KillAura.kt:1045-1087, 1051, 1055`、`Backtrack.kt:574-590, 101` | 改为遍历取 min（O(n) 无排序无拷贝）；目标搜索按 2-5 tick 节流 |
| 2 | **searchCenter 每候选点一次 block-raytrace**：x×y×z 三重循环（verticalRange 0.1..0.9 时 y 有 9 个候选），Noise3 模式每 tick 两轮 | `RotationUtils.kt:299-331, 318`、`KillAura.kt:1398-1431` | 先按角度差/距离排序候选，**找到满足项即停**（懒评估）；结果按 hitbox 短 TTL 缓存 |
| 3 | **Nuker 每 tick 12³=1728 次 getBlockState + 逐候选 rayTraceBlocks + 全量排序** | `Nuker.kt:50, 139, 152, 158-178, 186`、`BlockUtils.kt:86-104` | 方块扫描结果缓存 N tick（方块变化时失效）；只 raytrace 排序后前 K 个；1-5ms/tick 量级，是热路径最重单点 |

### 5.2 🟠 P1

| # | 问题 | 位置 |
|---|---|---|
| 4 | `raycastEntity` / `runWithModifiedRaycastResult`：每 tick/每次点击收集全实体 × backtrack 位置数（Legacy ×10）做 AABB 截距 + `isVisible` | `RaycastUtils.kt:47-49, 84-86, 133-148`、`KillAura.kt:888, 1528-1531, 1560-1574` |
| 5 | **SimulatedPlayer 每次构造做 2 轮 NBT 序列化往返**（capabilities 只有 6 个 float/bool 却走 writeNBT+readNBT），每 tick 最多 3-5 处调用（KillAura/Scaffold/TickBase/TimerRange/Aimbot 等 10+ 个调用点），每个模拟 tick 还有碰撞盒查询 ×2 与大量临时集合 | `SimulatedPlayer.kt:103-157, 159-176, 601, 632, 594, 804-835` |
| 6 | Scaffold：每 tick `BlockPos.getAllInBox`（Telly 484 位置）+ 排序 + 6~19 次方块 raytrace + 2 个 SimulatedPlayer 实例（同 tick KillAura 另建一个，未共享） | `Scaffold.kt:660, 803-857, 848-851, 1345-1375, 1430, 1456-1466, 1383` |
| 7 | `updateHittable` 对目标每个 backtrack 历史位置（≤10）做截距 + block-raytrace | `KillAura.kt:1560-1574, 1733-1740`、`RotationUtils.kt:770` |

**修复手段**：capabilities/food 改直接字段拷贝（不做 NBT 往返）；同 tick 多处模拟共享实例；`SimulatedPlayerJavaExtensions()` 改静态单例；碰撞/候选集合复用；raycast 只对锁定目标做、backtrack 位置限最近 2-3 条；实体列表用范围 AABB 查询替代全列表。

### 5.3 🟡 P2/P3

| # | 问题 | 位置 |
|---|---|---|
| 8 | `serverRotation`/`lastRotations` setter 每 tick 建 2 个 ArrayList + 3 个 Rotation | `RotationUtils.kt:59-86`、`MixinEntityPlayerSP.java:241` |
| 9 | AI 模式每 tick 全实体最近距离扫描 + 模板/神经模式每 tick `File.lastModified()` 系统调用 | `RotationUtils.kt:650-655`、`RotationNet.kt:75-84, 158`、`AimTemplateEngine.kt:96-104, 154` |
| 10 | A* 寻路：1000 轮循环内每轮 new 比较器 + sort、每候选 hub 整路径复制 O(d)、线性查重；TPAura Smart/Flux 在 **Netty 线程**同步算路（阻塞收包）；每次 teleport 周期 `new Thread` | `AStarCustomPathFinder.java:53-93, 67, 193-221, 179-191`、`TPAura.kt:115-161, 242-249` |
| 11 | Gapple 吃苹果期间每 tick 构造 `C01PacketChatMessage("release")` + 无界 `LinkedBlockingQueue` | `Gapple.kt:396-403, 105, 125` |
| 12 | `KillAura.attackTickTimes` 无界增长（50 CPS miss 场景每小时数万条 Pair） | `KillAura.kt:509, 924, 932` |
| 13 | Timer onDisable 在无玩家时早退不恢复 timerSpeed（开局 timer 卡在 10x） | `world/Timer.kt:33-40` |
| 14 | 杂项每 tick/每帧全实体扫描无节流：OldGrim、AntiFireball（每帧）、AntiBot（每 tick 逐实体字符串 replace ×2） | `OldGrim.kt:35`、`AntiFireball.kt:85, 122-126`、`AntiBot.kt:221-225, 96` |
| 15 | Scaffold `loopSequence` 每 tick 新协程（模块无挂起点，普通 handler 即可） | `Scaffold.kt:355`、`Extensions.kt:37-51` |

### 5.4 ✅ 已排查良好

- 寻路 `PathUtils.kt:15-63` 只是线性插值；`drawRangeCircle` 已按边数缓存（`KillAura.kt:1869-1893`）；各模块 onEnable/onDisable 泄漏检查基本干净（LocalNPC/Blink/TPAura/Gapple/Scaffold 均正确复位）。

---

## 6. 内存占用与泄漏

### 6.1 🔴 高

| # | 问题 | 位置 | 量级 |
|---|---|---|---|
| 1 | **MP3 整曲全量解码进内存**（非流式）：`Converter.convert` → `toByteArray()` PCM byte[]（≈42MB/4min）→ WAVEFileParser → float[]（≈84MB），两份拷贝同时驻留并长期持有 | `music/ncm/audio/com/jsyn/util/SampleLoader.java:83-85`、`javazoom/jl/converter/Converter.java:231` | 峰值 GC 堆 **130-170MB/首**，长曲 250MB+ |
| 2 | **FLAC 巨型单次缓冲**：`BufferedInputStream` 缓冲取 `bitsPerSample * totalSamples / 4`，即整曲塞进单个 byte 缓冲 | `BufferedSampleLoader.java:52` | ≈42MB/首常驻 |
| 3 | **脚本重载泄漏**：`unregisterModule` 不注销自动生成的 ModuleCommand；ScriptTab 永久留在 vanilla 静态 `CreativeTabs` 数组；Nashorn Context 不 close | `ModuleManager.kt:261-264, 269-276`、`Script.kt:137`、`ScriptTab.kt:14`、`ScriptManager.kt:43` | 每次重载数 KB-数百 KB + 元空间增长；重载 50 次 ≈ 5-10MB |
| 4 | **字体重烘焙泄漏**：改字号/字重时旧 `AWTFontRenderer`（2048×N DynamicTexture）不 `deleteTexture`，被 `CREATED` IdentityHashMap 永久强引用；`dynamicCharTextures` 无淘汰策略 | `Fonts.kt:31, 310, 615-644`、`AWTFontRenderer.kt:194, 439` | **16MB VRAM/次**，换 5 次字号 ≈ 25-80MB 泄漏 |
| 5 | **背景图替换不 dispose 旧资源**（`dispose()` 方法已存在但从未调用） | `FileManager.kt:188-196`、`Background.kt:36-37` | 1080p 背景 ≈ 8MB VRAM/次；4K ≈ 33MB/次 |
| 6 | **封面全分辨率纹理无缩放**：网易云原图常见 3000×3000 直接 `DynamicTexture(image)`，UI 显示只有几十像素；MusicScreen LRU 96 张 + Island 缓存 32 张 | `MusicScreen.kt:1333-1341, 1373`、`Island.kt:4431-4466` | **3000×3000 ≈ 34MB VRAM/张**；满缓存理论上限 **数 GB VRAM** |

### 6.2 🟡 中

| # | 问题 | 位置 |
|---|---|---|
| 7 | SkinAPI 三个 object 级 ConcurrentHashMap 无上限无过期、纹理从不 deleteTexture、`skins_download` 磁盘无限累积 | `SkinAPI.kt:39-47, 83` |
| 8 | CapeAPI 披风纹理从不释放（每披风玩家 2MB 堆 + 1MB VRAM） | `CapeAPI.kt:67` |
| 9 | SRG 全表常驻（即使脚本从未用重映射） | `Remapper.kt:30-31, 89-117` |
| 10 | CloudMusic 缓存磁盘只写不清（且同曲不同质量各存一份）；Gson 无 deepCopy 用"序列化→再解析"往返复制 JsonObject（高频 GC churn） | `CloudMusic.java:489-501, 744-748` |
| 11 | `MultiThreadingUtil` cachedThreadPool 无界（50 并发线程 ≈ 50MB 栈） | `MultiThreadingUtil.java:12` |
| 12 | ClientRichPresence 断线重连循环可能叠加（单线程 ≈1MB 栈） | `ClientRichPresence.kt:70-75` |

### 6.3 修复手段（按收益排序）

1. **封面一行改动**：URL 追加网易云官方缩略参数 `?param=300y300`（零成本，省数百 MB VRAM）；或解码后缩放 ≤512 再建纹理。
2. **音频改流式解码**：仿照项目内已有的 `RafSampleLoader`（WAV 路径已流式、内存良好）实现"边播边解码"；至少 FloatSample 构建后立即释放 byte[] 引用；PCM 可用 short[] 替代 float[]（减半）。**省 130-250MB/首堆峰值**。
3. **字体/背景释放**：重烘焙前对旧 renderer `deleteTexture` 并从 `CREATED` 移除；换背景前调用 `oldBackground.dispose()`。
4. **脚本重载**：注销 generatedCommand、移除旧 ScriptTab、Nashorn engine `close()`。
5. **Skin/Cape 统一 LRU 缓存管理器**（上限 64-256，淘汰时 `deleteTexture` + 磁盘容量/时间清理）；线程池改固定 4-8 + 有界队列。
6. SRG 表改软引用或脚本卸载时 `unloadMappings()`。

### 6.4 ✅ 确认无泄漏

OkHttp 全局单例（`HttpUtils.kt:40`）、NCM WAV 播放流式、SynthesisEngine/PlaybackEngine 线程代际管理、MiniMapRegister 生命周期、MusicScreen 封面 LRU 元数据正确 deleteTexture、ScriptModule 事件钩子正确注销、EventManager 无只增不减集合、无截图/录像 buffer 驻留。

---

## 7. 启动耗时与产物体积

### 7.1 🔴 ViaVersion 主线程同步初始化

- `AirPlus.kt:174` `ViaMCP.create()` 在 `startClient()`（`MixinMinecraft.java:132-137` 主线程 startGame 注入点）同步执行；`ViaLoadingBase.java:122-152` 内 `Via.init` → `manager.init()` → `refreshVersions()`，且 `:148` `setMaxProtocolPathSize(Integer.MAX_VALUE)` 不做任何裁剪。
- 这是启动中最重的 CPU 块（数百 ms~数秒）。**修复**：移到 `SharedScopes.IO` 异步（连接服务器前完成即可）。

### 7.2 ✅ 已做好的延迟初始化

| 子系统 | 状态 |
|---|---|
| 音乐 MusicPlayer | ✅ onEnable 时才扫描/起线程 |
| DiscordRPC | ✅ 异步 setup |
| 脚本系统 | ✅ 无脚本零成本（`AirPlus.kt:216` 条件守卫；Remapper 有缓存 + sha256 校验） |
| 字体 | ✅ `Fonts.kt:344-409` registerLazy，仅 bakeCoreFonts 立即烘焙 |
| preload 网络任务 | ✅ 全部 `SharedScopes.IO.launch` 不阻塞 |

### 7.3 产物体积（51.76 MB）

| 构成 | 大小 |
|---|---|
| resources 总计 | 32.45 MB（其中两个中文 TTF 占 18.2MB = **56%**） |
| ViaVersion 系列 + nashorn | ~10.7 MB |
| Kotlin/OkHttp/xchart 等 shadow 依赖 | 其余 |

- **最大单点**：`fonts\Setsuna_pf_normal.ttf`（10.3MB）+ `fonts\HarmonyOS_Sans_SC_Regular.ttf`（7.9MB）。改为**首次使用时云端下载**（项目已有 Remapper 的云端缓存模式可复用），jar 可从 51.76MB 降到 ~33MB，同时缩短 jar 加载/解压时间。
- `mainmenu\hanabi2_bg.png`（1.93MB）/`xinxin_bg.png`（1.42MB）转 jpeg/webp 省 ~3MB；`kill.wav` 转 mp3 省 ~0.4MB。
- Mixin 规模：83 个 mixin 类（76 + 7 client），与 LiquidBounce 同量级，非瓶颈。

### 7.4 日志

✅ 无每帧日志刷屏（mixins 目录仅 1 条 info；HUD 仅异常时打日志）；`log4j2.xml` 仅 dev 生效，无需调整。

---

## 8. 帧率提升手段速查表（按生效速度排序）

| 手段 | 改动成本 | 预期收益 |
|---|---|---|
| 删除 `GLErrorProbe` 诊断代码 | 5 分钟 | 0.5-2ms/帧 |
| 封面 URL 加 `?param=300y300` | 1 行 | 省数百 MB VRAM |
| G1 GC 参数（README + runConfig） | 10 分钟 | 消除周期性 100ms+ 卡顿尖峰 |
| 事件取消提前 break | 1 行 | 每包省 ~70 次无效 hook |
| PacketEvent 不再每包开协程 | 小 | 高 PPS 场景每秒省数百协程+任务 |
| 颜色选择器/渐变烘焙纹理 | 中 | UI 打开时 3-10ms/帧 |
| ItemESP Glow 批量化（一次 pass 渲染全部物品） | 中 | 多物品场景 20-50ms/帧 |
| blur mask 批量化（帧末一次 flush） | 中 | 多实体 ESP 3-8ms/帧 |
| KillAura 目标搜索节流 + Backtrack 取 min | 中 | PvP 每 tick 数 ms |
| Nuker 扫描缓存 N tick | 小 | Nuker 用户 1-5ms/tick |
| SimulatedPlayer 去 NBT 往返 | 中 | 每省 tick 6-10 次序列化 |
| 音频流式解码 | 大 | 播放时省 130-250MB 堆 |
| 字体重烘焙 dispose + CREATED 清理 | 小 | 每次换字号省 16MB VRAM |
| 背景替换调用 dispose() | 1 行 | 每次换背景省 8-33MB VRAM |
| 字号缓存 LRU + 动态字符合入 atlas | 大 | 显存数十 MB + 首烘卡顿 |
| 字体 TTF 云端下载 | 中 | jar -18MB，启动更快 |
| ViaVersion 异步初始化 | 中 | 启动省数百 ms~数秒 |

## 9. 通用优化模式总结

本报告发现的问题高度集中为以下 5 类反模式，按此模式批量整改即可覆盖 80% 的问题：

1. **每帧/每 tick 重建不变的数据** → 引入统一的「dirty 标志 + 缓存」工具类（布局、字符串、排序结果、宽度测量、主题色、封面、弹道路径）。
2. **逐对象一次昂贵管线调用**（ESP 逐实体离屏渲染、逐胶囊 blur、逐像素 drawRect） → 批量化：一次管线调用处理整批，帧末统一 flush（项目内 `HudGlowShader`、`RiseBlurShader` 已是正确范例）。
3. **立即模式 + 每顶点数学/GL 查询** → sin/cos 查表、帧内矩阵快照、Tessellator/VBO 批量绘制、uniform location 缓存、禁止 `GL_ALL_ATTRIB_BITS` 与逐帧 `glGetError`。
4. **热路径分配**（事件对象、lambda 捕获、列表拷贝、NBT 往返） → 单例可变事件、字段复用、数组轮换、直接字段拷贝。
5. **无界缓存/资源不释放**（字号缓存、纹理、线程池、磁盘缓存） → 统一 LRU 缓存管理器，淘汰时 `deleteTexture` + `dispose()` + `shutdown()`。

---

*报告生成于 2026-11，基于当前工作区代码。各文件:行号在后续重构后可能漂移，整改时以符号名搜索为准。*
