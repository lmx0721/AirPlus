# AirPlus AI 学习转头 - 离线训练脚本 (v4)
#
# 读取客户端录制的 rotations/dataset.json，按段分类（初次定位 flick / 追踪 track /
# 微调 fine，阈值与客户端 AimSegmentClass 三处共用）分别训练三个 9->24->24->2 的
# 小 MLP，输出 rotations/net_weights.json（version=4 classes 布局）供客户端
# RotationNet 推理。客户端只接受 v4 布局（v1/v2/v3 输入语义不同，直接拒绝）。
#
# 依赖：仅 numpy（pip install numpy）
# 用法：python train.py [dataset.json 路径] [-o net_weights.json 输出路径]
#
# 输入特征（v4，raw，除以各自 inputNorm 后进网络；与 RotationNet v4 逐维对应）:
#   0: remDiff  剩余角度差幅值（恒正）。优先用录制逐 tick 快照 needed(i-1)
#      （预测决策时机的真实剩余差），快照缺失（invalidate 零样本 tick）时回退
#      段内直线近似（后续增量之和）  norm = 数据 95 分位（下限 10）
#   1: velAlong 上一 tick 速度在剩余差方向上的投影（正=正在接近目标）
#                                        norm = 数据 95 分位（下限 1）
#   2: velCross 上一 tick 速度在正交方向上的投影（路径弯曲度）
#                                        norm = 数据 95 分位（下限 1）
#   3: dirYaw   剩余差方向单位向量 yaw 分量（保留「水平/垂直甩动」先验）
#                                        norm = 数据 95 分位（下限 0.1）
#   4: dirPitch 方向单位向量 pitch 分量    norm = 数据 95 分位（下限 0.1）
#   5: entityDist 最近实体距离 (格)        norm = 数据 95 分位（下限 1）
#   6: movingProgress = 0.1*moving + 0.9*progress（合并移动标志与进度）norm = 1
#   7: driftAlong  瞄准点漂移在 dir 径向上的投影  norm = 数据 95 分位（下限 1）
#   8: driftCross  瞄准点漂移在 dir 横向上的投影  norm = 数据 95 分位（下限 1）
#
# 分类（scene 场景真值，三端与 Kotlin AimScene/AimSegmentClass 同源，无启发式兜底）：
#   录制端（RotationRecorder）逐 tick 记录 TickSample.scene = 决策时刻的观测真值：
#     scene=3 追踪 track: 准星正压在实体 hitbox 上（AimBall 判定圈/射线-AABB 相交且可见）
#     scene=1 定位 flick: 不在目标上且剩余差 >= 12°（大角度甩向新目标）
#     scene=2 调整 fine:  不在目标上且剩余差 < 12°（贴身小角度修正）
#   pair 级标签取 scene(i-1)（决策时机，与特征同源）。开发版本无旧格式兼容：
#   只认 scene 真值，scene==0（无目标上下文，如空地乱转）的 tick 不构建监督对。
#
# 瞄准点漂移定义（三处共用，与 Kotlin/运行时同一公式同一 shift）：
#   d(i-1) = needed(i-1) - needed(i-2) + delta(i-1)
#   采样时机：录制器在 GameTickEvent 末尾快照 needed —— needed(j) 是第 j tick
#   **转动完之后**的剩余差。因此「预测增量 i」的特征一律取 (i-1) 状态，漂移描述
#   「上一 tick」的瞄准点移动（本 tick 的漂移要等转动完才知道，属于标签不可用）。
#   delta 项补偿本 tick 自身旋转（扣掉人手转出来的量才是纯瞄准点移动）。
#   漂移必须用未叠加 wander 噪声的 raw needed（客户端录制侧保证）；|d| 超过
#   25°/tick 视为间断（换目标/invalidate 零样本混入），按 0 处理。
#
# 不变式（三处共用：训练 / rollout_eval / 运行时 RotationNet.predict）：
#   dir = (remYaw, remPitch) / remDiff 是剩余差方向的单位向量，velAlong/velCross
#   与 driftAlong/driftCross 的投影方向必须与 dir 定义在同一坐标系：
#     velAlong   = velYaw*dirYaw + velPitch*dirPitch     （径向，正=正在接近目标）
#     velCross   = velYaw*(-dirPitch) + velPitch*dirYaw  （横向，路径弯曲度）
#     driftAlong = driftYaw*dirYaw + driftPitch*dirPitch （漂移径向投影）
#     driftCross = driftYaw*(-dirPitch) + driftPitch*dirYaw（漂移横向投影）
#   dir：训练 build_pairs 用段总方向（段首锁定），rollout_eval 与运行时 Run 创建
#   同理锁定不重算。速度语义必须与客户端运行时完全一致 —— vel = lastRotations[0]
#   - lastRotations[1]，即上一 tick 的实际带符号增量（度/tick）。
#   绝不能用当前 tick 的 yawDelta/pitchDelta 计算速度 —— 那是标签本身，属于特征
#   泄漏，网络会学到恒等映射，训练指标漂亮但运行时分布漂移、完全不可用。
#   对齐分解与绝对朝向解耦：网络学「径向速度剖面 + 瞄准点漂移 + 横向修正」。
#
# 输出（gcd 单位）:
#   0: 下一 tick yawDelta / gcd
#   1: 下一 tick pitchDelta / gcd

import json
import math
import sys
import argparse

import numpy as np

# 与客户端 AimSegmenter 一致的分段阈值
ENTER = 0.8    # 进入段：speed > ENTER (gcd 单位)
EXIT = 0.4     # 退出滞回：连续 3 tick speed < EXIT
EXIT_TICKS = 3
MIN_LENGTH = 3
MAX_LENGTH = 40
GAP_TICKS = 2

# 分类阈值（与 Kotlin AimSegmentClass / AimScene 镜像，改必须三处同步）
FLICK_MIN_DIFF_DEG = 12.0
DRIFT_DISCONTINUITY_DEG = 25.0

# 场景标签（Recording 端 AimScene 逐 tick 观测真值，与 Kotlin AimSegmentClass.fromScene
# 镜像）：1 = 定位(flick) 2 = 调整(fine) 3 = 追踪(track)。只认 scene 真值 ——
# scene==0（无目标上下文，如空地乱转）的 tick 不构建监督对；无启发式兜底
# （开发版本无旧格式兼容），0 在 dict 里缺席。
SCENE_TO_CLASS = {1: "flick", 2: "fine", 3: "track"}

# inputNorm 下限（v4 九维布局）：防止某特征分布过窄除出极大值；
# movingProgress 恒为 1
NORM_FLOOR = np.array([10.0, 1.0, 1.0, 0.1, 0.1, 1.0, 1.0, 1.0, 1.0], dtype=np.float64)

HIDDEN = 24
EPOCHS = 400
BATCH = 128
LR = 1e-3
PATIENCE = 30
VAL_SPLIT = 0.1
SEED = 42

# 类别训练样本下限：低于跳过该类（不导出模型 → 客户端该类回退 Default 管线）
MIN_CLASS_PAIRS = 50
# 低于该量训练告警（仍训练）
WARN_CLASS_PAIRS = 300
# 验证对本数过低时回退成对内百分比切分（按段切分可能把小类全部切进训练集）
MIN_VAL_PAIRS = 20

CLASS_ORDER = ("flick", "track", "fine")


def tick_speed_deg(t):
    """单 tick 的实际增量幅值（度/tick）。与客户端运行时 vel 的语义一致。"""
    return math.hypot(t["yawDelta"], t["pitchDelta"])


def needed_of(t):
    """逐 tick 瞄准点快照。invalidate 零样本 tick 快照为 (0, 0)。"""
    return (t.get("neededYaw", 0.0), t.get("neededPitch", 0.0))


def project_vel(yaw_vel, pitch_vel, dir_yaw, dir_pitch):
    """在剩余差方向坐标系下把速度分解为（径向, 横向）投影。

    不变式：投影必须与 dir 定义在同一坐标系。别处（rollout_eval /
    客户端 RotationNet.predict）必须使用同一公式。
    """
    vel_along = yaw_vel * dir_yaw + pitch_vel * dir_pitch
    vel_cross = yaw_vel * -dir_pitch + pitch_vel * dir_yaw
    return vel_along, vel_cross


def project_drift(drift_yaw, drift_pitch, dir_yaw, dir_pitch):
    """瞄准点漂移在 dir 坐标系下的（径向, 横向）投影。公式与 project_vel 同构。"""
    drift_along = drift_yaw * dir_yaw + drift_pitch * dir_pitch
    drift_cross = drift_yaw * -dir_pitch + drift_pitch * dir_yaw
    return drift_along, drift_cross


def segment(ticks):
    """与 Kotlin AimSegmenter.segment 相同的状态机，返回段索引列表 [(start, end_exclusive)]。

    MAX_LENGTH 行为必须与 Kotlin 一致：达到段长上限时**原地切分**并以 GAP_TICKS
    重新进入（后续高速 tick 可再开新段），而不是静默丢弃上限之外的 tick ——
    否则训练 pair 分布与 library.json（Kotlin 分段）在长甩头/粘滞追踪段上系统分歧。
    """
    segments = []
    in_segment = False
    gap_left = 0
    exit_counter = 0
    start = 0

    def speed_of(i):
        t = ticks[i]
        gcd = t.get("gcd") or 0.0
        if gcd <= 0:
            return 0.0
        return math.hypot(t["yawDelta"], t["pitchDelta"]) / gcd

    for i, _ in enumerate(ticks):
        speed = speed_of(i)

        if in_segment:
            if speed < EXIT:
                exit_counter += 1
                if exit_counter >= EXIT_TICKS:
                    end = i - EXIT_TICKS + 1
                    if end - start >= MIN_LENGTH:
                        segments.append((start, min(end, start + MAX_LENGTH)))
                    in_segment = False
                    gap_left = GAP_TICKS
                    exit_counter = 0
            else:
                exit_counter = 0

            # 与 Kotlin 相同的顺序：exit 计数处理之后，段长达到上限 → 原地切分
            if in_segment and (i - start + 1) >= MAX_LENGTH:
                segments.append((start, i + 1))
                in_segment = False
                gap_left = GAP_TICKS
                exit_counter = 0
        else:
            if gap_left > 0:
                gap_left -= 1
            elif speed > ENTER:
                in_segment = True
                start = i
                exit_counter = 0

    if in_segment:
        end = len(ticks)
        if end - start >= MIN_LENGTH:
            segments.append((start, min(end, start + MAX_LENGTH)))

    return segments


def build_pairs(ticks):
    """把段内 tick 转成监督对（含类别标签）。

    特征 0-4（对齐分解）：dir 用段总方向（段首锁定，与运行时 Run 创建时锁定一致）；
    velAlong/velCross 在该坐标系下投影。速度 = 上一 tick 的实际带符号增量
    （度/tick）：段内取 ticks[i-1]，段首取 ticks[start-1]（段外上一 tick，
    与运行时「启动那一刻的上一 tick 速度」同源）。剩余差优先用录制快照
    needed(i-1)（真实决策时机状态），快照缺失回退直线近似。
    漂移 = drift(i-1) = needed(i-1) - needed(i-2) + delta(i-1)，两级 needed 快照
    都可用才计算（逐 tick 判定，配合间断阈值）。
    moveSpeed/entityDist 同样按决策时机取 (gi-1) tick 的快照（与运行时引擎在决策
    时刻读到的玩家/环境状态一致）。
    类别标签同样取决策时机：scene(i-1)（录制端 AimScene 观测真值：1 定位 / 2 调整 /
    3 追踪）。只认 scene 真值：scene==0（无目标上下文，如空地乱转）的 tick 不构建
    监督对，无启发式兜底（开发版本无旧格式兼容）。
    """
    xs, ys, seg_ids, labels = [], [], [], []
    scene_skipped = 0

    def need_known(nt):
        """needed 快照是否可用：（0,0) 是录制端 invalidate 的零样本 tick
        （AI 接管/无玩家上下文时快照置零）。**必须逐 tick** 判定快照非零，不能用
        全局条件做闸门：这些 tick 若混入会以 rem_diff=0 计（判不出定位）且漂移
        公式退化为「鼠标增量」直接喂进网络（特征泄漏）。
        （真值 needed 恰为 (0,0) 的 tick 极罕见：意味着完全瞄准锚点、无剩余差，
        这种 tick 本来就不会出现在分出的段里。）"""
        return nt[0] != 0.0 or nt[1] != 0.0

    for sid, (start, end) in enumerate(segment(ticks)):
        if end - start < MIN_LENGTH:
            continue

        seg = ticks[start:end]
        seg_yaw = sum(t["yawDelta"] for t in seg)
        seg_pitch = sum(t["pitchDelta"] for t in seg)
        seg_diff = math.hypot(seg_yaw, seg_pitch)
        if seg_diff <= 0:
            continue  # 段内增量完全抵消时 dir 未定义，跳过（运行时由 MIN_DIFF_TO_START 保护）
        dir_yaw = seg_yaw / seg_diff
        dir_pitch = seg_pitch / seg_diff
        rem_yaw = seg_yaw
        rem_pitch = seg_pitch

        # S3：训练侧 movingProgress 的 duration 必须用运行时 RotationNet.predict 的
        # Run 创建公式（neededDiff / max(speed, 3)，clamp 3..40），而不是段长 len(seg)
        # —— 运行时 Run 时长/进度走的都是这一条公式，段长与它可能差数倍；
        # 进度以 Run 内 tick 计数取模（运行时 Run 结束会重建 Run、tick 归零）。
        speed0 = tick_speed_deg(ticks[start - 1]) if start > 0 else 0.0
        duration = max(3, min(40, round(seg_diff / max(speed0, 3.0))))

        prev_yaw_vel = ticks[start - 1]["yawDelta"] if start > 0 else 0.0
        prev_pitch_vel = ticks[start - 1]["pitchDelta"] if start > 0 else 0.0
        prev_need = needed_of(ticks[start - 1]) if start > 0 else None
        prev_prev_need = needed_of(ticks[start - 2]) if start > 1 else None
        prev_ctx = ticks[start - 1] if start > 0 else None

        for i, t in enumerate(seg):
            gi = start + i
            gcd = t.get("gcd") or 0.0

            ctx = prev_ctx if prev_ctx is not None else t
            moving = 1.0 if ctx.get("moveSpeed", 0.0) > 0.1 else 0.0
            vel_along, vel_cross = project_vel(prev_yaw_vel, prev_pitch_vel, dir_yaw, dir_pitch)

            drift_yaw = drift_pitch = 0.0
            if prev_need is not None and prev_prev_need is not None \
                    and need_known(prev_need) and need_known(prev_prev_need):
                d_y = prev_need[0] - prev_prev_need[0] + prev_yaw_vel
                d_p = prev_need[1] - prev_prev_need[1] + prev_pitch_vel
                if math.hypot(d_y, d_p) <= DRIFT_DISCONTINUITY_DEG:
                    drift_yaw, drift_pitch = d_y, d_p

            rem_diff = math.hypot(rem_yaw, rem_pitch)
            if prev_need is not None and need_known(prev_need):
                rem_diff = math.hypot(prev_need[0], prev_need[1])

            drift_along, drift_cross = project_drift(drift_yaw, drift_pitch, dir_yaw, dir_pitch)

            # gcd 无效的 tick 不能成对（除零）；scene==0（无目标上下文）同样不成对
            # —— 无启发式兜底。跳过的 tick 状态推进照常，保持漂移/速度连续性。
            # xs/ys/seg_ids/labels 必须严格同步追加，任何一侧单边追加都会错位。
            ctx_scene = ctx.get("scene", 0) or 0
            if gcd > 0:
                if ctx_scene in SCENE_TO_CLASS:
                    xs.append([
                        rem_diff,
                        vel_along, vel_cross, dir_yaw, dir_pitch,
                        ctx.get("entityDist", 0.0),
                        0.1 * moving + 0.9 * ((i % duration) / duration),  # S3：运行时 Run 进度
                        drift_along, drift_cross,
                    ])
                    ys.append([t["yawDelta"] / gcd, t["pitchDelta"] / gcd])
                    seg_ids.append(sid)
                    labels.append(SCENE_TO_CLASS[ctx_scene])
                else:
                    scene_skipped += 1

            # 状态推进（与执行顺序无关：特征先取上一 tick 状态，再推进）
            prev_prev_need = prev_need
            prev_need = needed_of(t)
            prev_yaw_vel = t["yawDelta"]
            prev_pitch_vel = t["pitchDelta"]
            prev_ctx = t
            rem_yaw -= t["yawDelta"]
            rem_pitch -= t["pitchDelta"]

    if not ys:
        return np.zeros((0, 9)), np.zeros((0, 2)), np.zeros(0, dtype=int), []
    total_seen = len(labels) + scene_skipped
    print(f"[data] all {len(labels)} pairs labelled by scene ground truth "
          f"({sum(1 for l in labels if l == 'flick')} flick / "
          f"{sum(1 for l in labels if l == 'fine')} fine / "
          f"{sum(1 for l in labels if l == 'track')} track)")
    if scene_skipped:
        print(f"[data] {scene_skipped}/{total_seen} gcd-valid ticks skipped "
              f"(scene==0, no target context - play more with AimBall to grow coverage)")
    return (np.array(xs, dtype=np.float64), np.array(ys, dtype=np.float64),
            np.array(seg_ids), labels)


def compute_input_norm(xs):
    """按数据实际分布校准归一化：95 分位（含下限），随权重一起导出。
    hardcode 的下限会让大甩头/远距离样本归一化后 >1，tanh 立即饱和。"""
    norm = np.percentile(np.abs(xs), 95, axis=0)
    norm = np.maximum(norm, NORM_FLOOR)
    norm[6] = 1.0  # 维 6 = movingProgress（0.1*moving + 0.9*progress），恒为 1
    return norm


class MLP:
    def __init__(self, sizes):
        rng = np.random.default_rng(SEED)
        self.w, self.b = [], []
        for fan_in, fan_out in zip(sizes, sizes[1:]):
            # He/Xavier 折中，tanh 用 Xavier 更稳
            limit = math.sqrt(6.0 / (fan_in + fan_out))
            self.w.append(rng.uniform(-limit, limit, (fan_in, fan_out)))
            self.b.append(np.zeros(fan_out))

    def forward(self, x):
        """返回 (输出, 各层激活缓存)。隐层 tanh，输出线性。"""
        activations = [x]
        a = x
        for i, (w, b) in enumerate(zip(self.w, self.b)):
            z = a @ w + b
            a = np.tanh(z) if i < len(self.w) - 1 else z
            activations.append(a)
        return a, activations

    def predict(self, x):
        return self.forward(x)[0]

    def train_step(self, x, y, lr, m, v, t):
        out, activations = self.forward(x)
        n = x.shape[0]

        delta = (out - y) / n  # MSE 对输出层的梯度
        grads_w = [None] * len(self.w)
        grads_b = [None] * len(self.b)

        for i in range(len(self.w) - 1, -1, -1):
            grads_w[i] = activations[i].T @ delta
            grads_b[i] = delta.sum(axis=0)
            if i > 0:
                delta = (delta @ self.w[i].T) * (1 - activations[i] ** 2)  # tanh'

        beta1, beta2, eps = 0.9, 0.999, 1e-8
        for i in range(len(self.w)):
            m[0][i] = beta1 * m[0][i] + (1 - beta1) * grads_w[i]
            m[1][i] = beta1 * m[1][i] + (1 - beta1) * grads_b[i]
            v[0][i] = beta2 * v[0][i] + (1 - beta2) * grads_w[i] ** 2
            v[1][i] = beta2 * v[1][i] + (1 - beta2) * grads_b[i] ** 2

            m_hat_w = m[0][i] / (1 - beta1 ** t)
            m_hat_b = m[1][i] / (1 - beta1 ** t)
            v_hat_w = v[0][i] / (1 - beta2 ** t)
            v_hat_b = v[1][i] / (1 - beta2 ** t)

            self.w[i] -= lr * m_hat_w / (np.sqrt(v_hat_w) + eps)
            self.b[i] -= lr * m_hat_b / (np.sqrt(v_hat_b) + eps)

        return float(np.mean((out - y) ** 2))


def train_one(x_train, y_train, x_val, y_val, rng_seed=SEED):
    """训练单个类别网络（early停基于 val MSE）。返回 (net, best_val)。"""
    net = MLP([9, HIDDEN, HIDDEN, 2])
    m = ([np.zeros_like(w) for w in net.w], [np.zeros_like(b) for b in net.b])
    v = ([np.zeros_like(w) for w in net.w], [np.zeros_like(b) for b in net.b])

    rng = np.random.default_rng(rng_seed)
    best_val = float("inf")
    best_state = None
    since_best = 0
    step = 0

    for epoch in range(1, EPOCHS + 1):
        perm = rng.permutation(len(x_train))
        epoch_loss = 0.0
        batches = 0

        for s in range(0, len(x_train), BATCH):
            idx = perm[s:s + BATCH]
            step += 1
            epoch_loss += net.train_step(x_train[idx], y_train[idx], LR, m, v, step)
            batches += 1

        val_pred = net.predict(x_val)
        val_mse = float(np.mean((val_pred - y_val) ** 2))

        if val_mse < best_val:
            best_val = val_mse
            best_state = ([w.copy() for w in net.w], [b.copy() for b in net.b])
            since_best = 0
        else:
            since_best += 1

        if epoch % 20 == 0 or epoch == 1:
            print(f"[train] epoch {epoch:4d}  loss {epoch_loss / batches:.6f}  val {val_mse:.6f}  best {best_val:.6f}")

        if since_best >= PATIENCE:
            print(f"[train] early stop at epoch {epoch} (no improvement for {PATIENCE} epochs)")
            break

    if best_state:
        net.w, net.b = best_state
    return net, best_val


def export_v4(models, path):
    """导出 v4 分类别布局：{"version":4,"classes":{"flick":{inputNorm,layers},...}}。
    跳过未训练的类别（客户端该类回退 Default）。全部类别缺失则不写盘。"""
    classes_payload = {}
    for name in CLASS_ORDER:
        if name not in models:
            continue
        net, input_norm = models[name]
        layers = []
        for i, (w, b) in enumerate(zip(net.w, net.b)):
            layers.append({
                "w": w.T.tolist(),  # 客户端按 w[out][in] 存
                "b": b.tolist(),
                "act": "tanh" if i < len(net.w) - 1 else "linear",
            })
        classes_payload[name] = {"inputNorm": input_norm.tolist(), "layers": layers}

    if not classes_payload:
        print("[export] no class models - nothing written")
        return

    payload = {"version": 4, "classes": classes_payload}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    for name in CLASS_ORDER:
        if name not in models:
            print(f"[export] class {name:5s}: SKIPPED (insufficient pairs)")
            continue
        _, norm = models[name]
        print(f"[export] class {name:5s}: inputNorm = {[round(v, 2) for v in norm.tolist()]}")
    print(f"[export] {path} written (version 4, {len(classes_payload)} classes)")


def rollout_eval(models, ticks):
    """整段甩动 rollout 评估，逐 tick 按同一 classify 路由到类别模型。比单点 MSE 更直观。

    对每个真实段，从段起点开始用训好的网络逐步生成增量（与客户端一致：
    对齐分解特征 / gcd 单位输出 / 单 tick 不超过剩余差），模拟到剩余 < 2° 或
    步数上限，统计最终残差与过冲率。dir 用段总方向锁定，rollout 中不重算
    （与运行时 Run 创建时锁定一致）。rollout 模拟的是静止瞄准点（无漂移），
    与客户端静态目标收尾分布一致；某步骤类别缺模型时该段记为 no-model。
    """
    MIN_DIFF = 2.0  # 与客户端 MIN_DIFF_TO_START 一致，之后交给 Default 管线收尾
    MAX_STEPS = 60

    residuals = []
    overshoot = 0
    steps_list = []
    skipped = 0

    for start, end in segment(ticks):
        if end - start < MIN_LENGTH:
            continue

        seg = ticks[start:end]
        total_diff = math.hypot(
            sum(t["yawDelta"] for t in seg), sum(t["pitchDelta"] for t in seg))
        if total_diff < MIN_DIFF:
            continue

        rem_yaw = sum(t["yawDelta"] for t in seg)
        rem_pitch = sum(t["pitchDelta"] for t in seg)
        dir_yaw = rem_yaw / total_diff
        dir_pitch = rem_pitch / total_diff

        # 与客户端 RotationNet.predict 的 duration 公式完全一致：
        # duration = neededDiff / max(上一tick速度, 3)，clamp 到 3..40
        speed0 = tick_speed_deg(ticks[start - 1]) if start > 0 else 0.0
        duration = max(3, min(40, round(total_diff / max(speed0, 3.0))))

        # 带符号上一 tick 速度（与客户端 vel = lastRotations[0] - lastRotations[1] 同源）
        prev_yaw_vel = ticks[start - 1]["yawDelta"] if start > 0 else 0.0
        prev_pitch_vel = ticks[start - 1]["pitchDelta"] if start > 0 else 0.0
        moved = 0.0
        i = 0

        for i in range(min(MAX_STEPS, max(duration * 3, 10))):
            gcd = seg[min(i, len(seg) - 1)].get("gcd") or 0.0
            if gcd <= 0:
                continue
            rem_diff = math.hypot(rem_yaw, rem_pitch)
            if rem_diff < MIN_DIFF:
                break

            ctx = seg[min(i, len(seg) - 1)]
            moving = 1.0 if ctx.get("moveSpeed", 0.0) > 0.1 else 0.0
            vel_along, vel_cross = project_vel(prev_yaw_vel, prev_pitch_vel, dir_yaw, dir_pitch)

            # 静态模拟：瞄准点漂移恒 0（与真实静止目标分布一致）。类别只按幅值路由
            # （scene 真值语义：剩余差 >= 12° 为定位，否则微调；不模拟「准星压在
            # 实体上」的追踪 —— rollout 只评估定位/微调两类的收敛质量）
            cls = "flick" if rem_diff >= FLICK_MIN_DIFF_DEG else "fine"
            if cls not in models:
                skipped += 1
                break
            net, input_norm = models[cls]

            x = np.array([
                rem_diff,
                vel_along, vel_cross, dir_yaw, dir_pitch,
                ctx.get("entityDist", 0.0),
                # 与训练/运行时一致：Run 内 tick 计数取模（运行时 Run 结束会重建）
                0.1 * moving + 0.9 * ((i % max(duration, 1)) / max(duration, 1)),
                0.0, 0.0,
            ], dtype=np.float64) / input_norm

            out = net.predict(x[None, :])[0]
            dy, dp = out[0] * gcd, out[1] * gcd

            # 与客户端一致的防过冲 clamp
            out_diff = math.hypot(dy, dp)
            if out_diff > rem_diff > 0:
                dy, dp = dy * rem_diff / out_diff, dp * rem_diff / out_diff

            rem_yaw -= dy
            rem_pitch -= dp
            prev_yaw_vel = dy
            prev_pitch_vel = dp
            moved += math.hypot(dy, dp)

        final_residual = math.hypot(rem_yaw, rem_pitch)
        residuals.append(final_residual)
        steps_list.append(i + 1 if final_residual >= MIN_DIFF else i)
        if moved > total_diff:
            overshoot += 1

    if not residuals:
        print("[eval] no segments to evaluate")
        return

    residuals.sort()
    n = len(residuals)
    print(f"[eval] rollout over {n} segments ({skipped} skipped: class model missing):")
    print(f"  final residual  mean {sum(residuals) / n:.2f}°  median {residuals[n // 2]:.2f}°  "
          f"p90 {residuals[int(n * 0.9)]:.2f}°  max {residuals[-1]:.2f}°")
    print(f"  overshoot rate  {overshoot / n * 100:.1f}%")
    print(f"  avg steps       {sum(steps_list) / len(steps_list):.1f}")


def main():
    parser = argparse.ArgumentParser(description="AirPlus aim MLP trainer (v4, per-class)")
    parser.add_argument("dataset", nargs="?", default="dataset.json")
    parser.add_argument("-o", "--out", default="net_weights.json")
    args = parser.parse_args()

    with open(args.dataset, "r", encoding="utf-8") as f:
        data = json.load(f)

    ticks = data.get("ticks", [])
    print(f"[data] {len(ticks)} ticks")

    x_all, y_all, seg_ids, labels = build_pairs(ticks)
    if len(x_all) == 0:
        print("[error] no training pairs - dataset has no valid turn segments")
        print("        Segmentation needs quick flicks (> 0.8 gcd/tick) recorded by RotationRecorder.")
        print("        Play with AimBall enabled for a while, then retrain.")
        sys.exit(1)
    if len(x_all) < 500:
        print(f"[warn] only {len(x_all)} training pairs - record more gameplay (>= 500 recommended)")

    counts = {c: 0 for c in CLASS_ORDER}
    for lb in labels:
        counts[lb] += 1
    print(f"[data] {len(x_all)} training pairs from {len(np.unique(seg_ids))} segments: "
          + ", ".join(f"{c}={counts[c]}" for c in CLASS_ORDER))

    # 验证集按「段」切分：同一段内相邻 tick 的输入几乎相同，
    # 按 tick 随机切分会让 val MSE 被乐观估计、early stop 触发过晚。
    rng = np.random.default_rng(SEED)
    uniq_segments = np.unique(seg_ids)
    n_val_seg = max(1, int(len(uniq_segments) * VAL_SPLIT))
    val_segments = rng.choice(uniq_segments, size=n_val_seg, replace=False)
    val_mask = np.isin(seg_ids, val_segments)
    print(f"[split] {len(uniq_segments)} segments -> {n_val_seg} val segments")

    models = {}
    for cls in CLASS_ORDER:
        cls_mask = np.array([lb == cls for lb in labels])
        n_cls = int(cls_mask.sum())
        if n_cls < MIN_CLASS_PAIRS:
            print(f"[warn] class {cls}: {n_cls} pairs < {MIN_CLASS_PAIRS} - skipped "
                  f"(client falls back to Default for this class)")
            continue
        if n_cls < WARN_CLASS_PAIRS:
            print(f"[warn] class {cls}: only {n_cls} pairs - model quality limited "
                  f"(record more {cls} gameplay)")

        xs_c = x_all[cls_mask]
        input_norm = compute_input_norm(xs_c)
        xs_c = xs_c / input_norm
        ys_c = y_all[cls_mask]
        segs_c = seg_ids[cls_mask]

        cls_val_mask = val_mask[cls_mask]
        if cls_val_mask.sum() < MIN_VAL_PAIRS:
            # 按段切分可能把小类全部切进训练集：先回退「类内按段」切分（同段相邻 tick
            # 输入几乎相同，pair 级随机仍会 train/val 泄漏），段数也太少时才用 pair 级。
            seg_rng = np.random.default_rng(SEED + CLASS_ORDER.index(cls))
            cls_uniq = np.unique(segs_c)
            n_val_seg = max(1, int(len(cls_uniq) * VAL_SPLIT))
            chosen_seg = seg_rng.choice(cls_uniq, size=min(n_val_seg, len(cls_uniq)), replace=False)
            cls_val_mask = np.isin(segs_c, chosen_seg)
            if cls_val_mask.sum() < MIN_VAL_PAIRS:
                pair_rng = np.random.default_rng(SEED + 7 + CLASS_ORDER.index(cls))
                perm = pair_rng.permutation(len(xs_c))
                n_val = max(MIN_VAL_PAIRS, int(len(xs_c) * VAL_SPLIT))
                cls_val_mask = np.zeros(len(xs_c), dtype=bool)
                cls_val_mask[perm[:n_val]] = True
                print(f"[split] class {cls}: too few segments ({len(cls_uniq)}), "
                      f"using pair-level {n_val} val pairs (may leak within segments)")
            else:
                print(f"[split] class {cls}: global segment split too sparse, "
                      f"using per-class segment split ({int(cls_val_mask.sum())} val pairs)")

        x_train, y_train = xs_c[~cls_val_mask], ys_c[~cls_val_mask]
        x_val, y_val = xs_c[cls_val_mask], ys_c[cls_val_mask]
        print(f"[train:{cls}] {n_cls} pairs  ->  {len(x_train)} train / {len(x_val)} val")

        net, best_val = train_one(x_train, y_train, x_val, y_val,
                                  SEED + CLASS_ORDER.index(cls))
        print(f"[done:{cls}] best val MSE = {best_val:.6f}")
        models[cls] = (net, input_norm)

    export_v4(models, args.out)
    rollout_eval(models, ticks)


if __name__ == "__main__":
    main()
