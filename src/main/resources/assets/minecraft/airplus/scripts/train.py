# AirPlus AI 学习转头 - 离线训练脚本
#
# 读取客户端录制的 rotations/dataset.json，训练 6->24->24->2 的小 MLP，
# 输出 rotations/net_weights.json 供客户端 RotationNet 推理。
#
# 依赖：仅 numpy（pip install numpy）
# 用法：python train.py [dataset.json 路径] [-o net_weights.json 输出路径]
#
# 输入特征（raw，除以 inputNorm 后进网络）:
#   0: 剩余 yaw 角度差 (度)        norm 60
#   1: 剩余 pitch 角度差 (度)      norm 60
#   2: 当前角速度 (度/tick)        norm 30
#   3: 最近实体距离 (格)           norm 16
#   4: 是否移动 (0/1)              norm 1
#   5: 旋转进度 (0-1)              norm 1
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

INPUT_NORM = np.array([60.0, 60.0, 30.0, 16.0, 1.0, 1.0], dtype=np.float64)

HIDDEN = 24
EPOCHS = 400
BATCH = 128
LR = 1e-3
PATIENCE = 30
VAL_SPLIT = 0.1
SEED = 42


def segment(ticks):
    """与 Kotlin AimSegmenter.segment 相同的状态机，返回段索引列表 [(start, end_exclusive)]。"""
    segments = []
    in_segment = False
    gap_left = 0
    exit_counter = 0
    start = 0
    prev_speed = 0.0

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
        else:
            if gap_left > 0:
                gap_left -= 1
            elif speed > ENTER:
                in_segment = True
                start = i
                exit_counter = 0

        prev_speed = speed

    if in_segment:
        end = len(ticks)
        if end - start >= MIN_LENGTH:
            segments.append((start, min(end, start + MAX_LENGTH)))

    return segments


def build_pairs(ticks):
    """把段内 tick 转成监督对。剩余角度差 = 段内后续增量之和（直线甩动近似）。"""
    xs, ys = [], []

    for start, end in segment(ticks):
        if end - start < MIN_LENGTH:
            continue

        seg = ticks[start:end]
        rem_yaw = sum(t["yawDelta"] for t in seg)
        rem_pitch = sum(t["pitchDelta"] for t in seg)
        duration = len(seg)

        for i, t in enumerate(seg):
            gcd = t.get("gcd") or 0.0
            if gcd <= 0:
                continue

            speed = math.hypot(t["yawDelta"], t["pitchDelta"]) / gcd
            moving = 1.0 if t.get("moveSpeed", 0.0) > 0.1 else 0.0

            xs.append([
                rem_yaw, rem_pitch, speed,
                t.get("entityDist", 0.0), moving, i / max(duration - 1, 1),
            ])
            ys.append([t["yawDelta"] / gcd, t["pitchDelta"] / gcd])

            rem_yaw -= t["yawDelta"]
            rem_pitch -= t["pitchDelta"]

    return np.array(xs, dtype=np.float64), np.array(ys, dtype=np.float64)


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


def export(net, path):
    layers = []
    for i, (w, b) in enumerate(zip(net.w, net.b)):
        layers.append({
            "w": w.T.tolist(),  # 客户端按 w[out][in] 存
            "b": b.tolist(),
            "act": "tanh" if i < len(net.w) - 1 else "linear",
        })

    payload = {
        "version": 1,
        "inputNorm": INPUT_NORM.tolist(),
        "layers": layers,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    print(f"[export] {path} ({len(layers)} layers)")


def rollout_eval(net, ticks):
    """整段甩动 rollout 评估：比单点 MSE 更直观。

    对每个真实段，从段起点开始用训好的网络逐步生成增量（与客户端一致：
    剩余角度差 / gcd 单位输出 / 单 tick 不超过剩余差），模拟到剩余 < 2° 或步数上限，
    统计最终残差与过冲率。
    """
    MIN_DIFF = 2.0  # 与客户端 MIN_DIFF_TO_START 一致，之后交给 Default 管线收尾
    MAX_STEPS = 60

    residuals = []
    overshoot = 0
    steps_list = []

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

        speed0 = math.hypot(seg[0]["yawDelta"], seg[0]["pitchDelta"]) / max(seg[0].get("gcd") or 0.0, 1e-6)
        duration = max(3, min(40, round(total_diff / max(speed0, 3.0))))
        moved = 0.0

        for i in range(min(MAX_STEPS, max(duration * 3, 10))):
            gcd = seg[min(i, len(seg) - 1)].get("gcd") or 0.0
            if gcd <= 0:
                continue
            rem_diff = math.hypot(rem_yaw, rem_pitch)
            if rem_diff < MIN_DIFF:
                break

            speed = moved / max(i, 1)  # 已走过的平均角速度，近似客户端的当前角速度
            ctx = seg[min(i, len(seg) - 1)]
            moving = 1.0 if ctx.get("moveSpeed", 0.0) > 0.1 else 0.0

            x = np.array([
                rem_yaw, rem_pitch, speed,
                ctx.get("entityDist", 0.0), moving, i / max(duration - 1, 1),
            ], dtype=np.float64) / INPUT_NORM

            out = net.predict(x[None, :])[0]
            dy, dp = out[0] * gcd, out[1] * gcd

            # 与客户端一致的防过冲 clamp
            out_diff = math.hypot(dy, dp)
            if out_diff > rem_diff > 0:
                dy, dp = dy * rem_diff / out_diff, dp * rem_diff / out_diff

            rem_yaw -= dy
            rem_pitch -= dp
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
    print(f"[eval] rollout over {n} segments:")
    print(f"  final residual  mean {sum(residuals) / n:.2f}°  median {residuals[n // 2]:.2f}°  p90 {residuals[int(n * 0.9)]:.2f}°  max {residuals[-1]:.2f}°")
    print(f"  overshoot rate  {overshoot / n * 100:.1f}%")
    print(f"  avg steps       {sum(steps_list) / len(steps_list):.1f}")


def main():
    parser = argparse.ArgumentParser(description="AirPlus aim MLP trainer")
    parser.add_argument("dataset", nargs="?", default="dataset.json")
    parser.add_argument("-o", "--out", default="net_weights.json")
    args = parser.parse_args()

    with open(args.dataset, "r", encoding="utf-8") as f:
        data = json.load(f)

    ticks = data.get("ticks", [])
    print(f"[data] {len(ticks)} ticks")

    x_all, y_all = build_pairs(ticks)
    if len(x_all) == 0:
        print("[error] no training pairs - dataset has no valid turn segments")
        print("        Segmentation needs quick flicks (> 0.8 gcd/tick) recorded by RotationRecorder.")
        print("        Play with AimBall enabled for a while, then retrain.")
        sys.exit(1)
    if len(x_all) < 500:
        print(f"[warn] only {len(x_all)} training pairs - record more gameplay (>= 500 recommended)")
    print(f"[data] {len(x_all)} training pairs")

    # 归一化进网络
    x_all = x_all / INPUT_NORM

    rng = np.random.default_rng(SEED)
    order = rng.permutation(len(x_all))
    x_all, y_all = x_all[order], y_all[order]

    n_val = max(1, int(len(x_all) * VAL_SPLIT))
    x_val, y_val = x_all[:n_val], y_all[:n_val]
    x_train, y_train = x_all[n_val:], y_all[n_val:]

    net = MLP([6, HIDDEN, HIDDEN, 2])
    m = (
        [np.zeros_like(w) for w in net.w],
        [np.zeros_like(b) for b in net.b],
    )
    v = (
        [np.zeros_like(w) for w in net.w],
        [np.zeros_like(b) for b in net.b],
    )

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

    export(net, args.out)
    print(f"[done] best val MSE = {best_val:.6f}")

    rollout_eval(net, ticks)


if __name__ == "__main__":
    main()
