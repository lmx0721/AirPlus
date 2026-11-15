/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight ArmorBreakerPlanner (NewArmorBreaker dependency).
 * Tick Hit 规划器：以带记忆化的前向搜索在攻击间隔/暴击窗口/冻结属性等约束下，
 * 选择下一次攻击的最佳武器与目标伤害，支持跳过（skip）与边缘风险混合。
 */
package net.airplus.features.module.modules.combat.armorbreak

internal class ArmorBreakerPlanner {
    val attr = FloatArray(36)
    val bonus = FloatArray(36)
    val sword = BooleanArray(36)
    var n = 0

    var interval = 10
    var horizon = 20
    var hitGap = 1

    var critNow = false
    var critNext = false
    val critPlan = BooleanArray(64)
    var critPlanLen = 0

    var allowSkip = false
    var skipMargin = 0.04
    var blocking = false
    var frozen = false
    var refreshMax = 0
    var refreshLeft = 0
    var edgeRisk = 0.0

    var predicted = 0.0f
    var accepted = false
    var skip = false
    var refresh = false

    private val attrs = FloatArray(37)
    private var attrCount = 0
    private val srcIds = IntArray(37)
    private var srcCount = 0

    private var vals = FloatArray(64)
    private var valCount = 0

    private var memoV = IntArray(0)
    private var stampV = IntArray(0)
    private var memoW = IntArray(0)
    private var stampW = IntArray(0)
    private var stamp = 0

    private var curAttrId = 0
    private var dimS = 0
    private var dimG = 0
    private var dimR = 1

    fun clear() {
        n = 0
    }

    fun add(attr: Float, bonus: Float, sword: Boolean) {
        this.attr[n] = attr
        this.bonus[n] = bonus
        this.sword[n] = sword
        n++
    }

    private fun loss(value: Float): Int {
        val effective = if (blocking) (1.0f + value) * 0.5f else value
        return Math.max(1, (effective / 4.0f).toInt())
    }

    private fun critAt(tick: Int): Boolean {
        return if (critPlanLen > 0) {
            tick < critPlanLen && critPlan[tick]
        } else {
            if (tick == 0) critNow else tick <= CRIT_RUN && critNext
        }
    }

    fun prepare(currentAttr: Float, currentAmount: Float) {
        attrCount = 0
        srcCount = 0
        val hasSword = sword.copyOf(n).any { it }

        for (i in 0 until n) {
            if (!hasSword || sword[i]) {
                val id = attrId(attr[i], true)
                if (srcIds.copyOf(srcCount).none { it == id }) {
                    srcIds[srcCount++] = id
                }
            }
        }

        curAttrId = attrId(currentAttr, true)

        val needed = 1 + attrCount * 2 * Math.max(1, n)
        if (vals.size < needed) {
            vals = FloatArray(needed)
        }

        valCount = 0
        vals[valCount++] = currentAmount

        for (a in 0 until attrCount) {
            for (w in 0 until n) {
                vals[valCount++] = amount(attrs[a], false, bonus[w])
                vals[valCount++] = amount(attrs[a], true, bonus[w])
            }
        }

        java.util.Arrays.sort(vals, 0, valCount)
        var kept = 0
        for (i in 0 until valCount) {
            if (kept == 0 || java.lang.Float.compare(vals[kept - 1], vals[i]) != 0) {
                vals[kept++] = vals[i]
            }
        }

        valCount = kept
        dimS = interval + 1
        dimG = Math.max(1, hitGap) + 1
        dimR = if (frozen) Math.max(0, refreshMax) + 1 else 1

        val size = (horizon + 1) * attrCount * dimR * dimS * (valCount + 1) * dimG
        if (memoV.size < size) {
            memoV = IntArray(size)
            stampV = IntArray(size)
        }

        if (memoW.size < size) {
            memoW = IntArray(size)
            stampW = IntArray(size)
        }

        if (++stamp == Int.MIN_VALUE) {
            java.util.Arrays.fill(stampV, 0)
            java.util.Arrays.fill(stampW, 0)
            stamp = 1
        }
    }

    private fun attrId(value: Float, create: Boolean): Int {
        for (i in 0 until attrCount) {
            if (java.lang.Float.compare(attrs[i], value) == 0) {
                return i
            }
        }

        if (!create) {
            return -1
        }

        attrs[attrCount] = value
        return attrCount++
    }

    private fun valId(value: Float): Int {
        val idx = java.util.Arrays.binarySearch(vals, 0, valCount, value)
        return if (idx >= 0) idx else 0
    }

    private fun gapCap(): Int = dimG - 1

    private fun open(gap: Int): Boolean = gap >= gapCap()

    private fun nextGap(gap: Int): Int = Math.min(gap + 1, gapCap())

    /** 状态：attrState = attrId * dimR + refreshRemainder。返回后续期望收益（整数刻度）。 */
    private fun value(tick: Int, attrState: Int, timer: Int, valIdx: Int, gap: Int): Int {
        if (tick >= horizon) {
            return 0
        }

        val attrIdx = attrState / dimR
        val key = (((tick * attrCount * dimR + attrState) * dimS + timer) * (valCount + 1) + valIdx) * dimG + gap
        if (stampV[key] == stamp) {
            return memoV[key]
        }

        val cooled = timer >= interval
        val heldValue = if (cooled) 0.0f else vals[valIdx]
        val crit = critAt(tick)
        var best = stage(tick + 1, Math.min(timer + 1, interval), valIdx, nextGap(gap), attrState)

        if (open(gap)) {
            val edge = isEdge(timer)

            for (w in 0 until n) {
                val amount = amount(attrs[attrIdx], crit, bonus[w])
                val score: Int
                if (!cooled && !(amount > heldValue)) {
                    if (!edge) {
                        continue
                    }

                    score = best
                } else {
                    val cost = if (cooled) loss(amount) else loss(amount - heldValue)
                    val nextTimer = if (cooled) 1 else Math.min(timer + 1, interval)
                    val nextState = if (cooled) attrIdx * dimR + dimR - 1 else attrState
                    score = cost * WEIGHT[tick] + stage(tick + 1, nextTimer, valId(amount), nextGap(0), nextState)
                }

                var mixed = score
                if (edge) {
                    mixed = edgeMix(mixed, tick, attrIdx, amount)
                }

                if (mixed > best) {
                    best = mixed
                }
            }
        }

        stampV[key] = stamp
        memoV[key] = best
        return best
    }

    private fun stage(tick: Int, timer: Int, valIdx: Int, gap: Int, attrState: Int): Int {
        if (tick >= horizon) {
            return 0
        }

        var valueIdx = valIdx
        if (timer >= interval) {
            valueIdx = valCount
        }

        var state = attrState
        if (!frozen) {
            state = 0
        }

        val key = (((tick * attrCount * dimR + state) * dimS + timer) * (valCount + 1) + valueIdx) * dimG + gap
        if (stampW[key] == stamp) {
            return memoW[key]
        }

        val best: Int
        if (frozen) {
            var result = value(tick, state, timer, valueIdx, gap)
            val attrIdx = state / dimR
            val remain = state % dimR
            if (remain > 0) {
                for (s in 0 until srcCount) {
                    if (srcIds[s] != attrIdx) {
                        result = Math.max(result, value(tick, srcIds[s] * dimR + remain - 1, timer, valueIdx, gap))
                    }
                }
            }

            best = result
        } else if (srcCount == 0) {
            best = value(tick, curAttrId * dimR, timer, valueIdx, gap)
        } else {
            var result = 0
            for (s in 0 until srcCount) {
                result = Math.max(result, value(tick, srcIds[s] * dimR, timer, valueIdx, gap))
            }

            best = result
        }

        stampW[key] = stamp
        memoW[key] = best
        return best
    }

    private fun isEdge(timer: Int): Boolean = edgeRisk > 0.0 && timer == interval - 1

    private fun edgeMix(noAttackScore: Int, tick: Int, attrIdx: Int, amount: Float): Int {
        val attackScore = loss(amount) * WEIGHT[tick] + stage(tick + 1, 1, valId(amount), nextGap(0), attrIdx * dimR + dimR - 1)
        return Math.round((1.0 - edgeRisk) * noAttackScore + edgeRisk * attackScore).toInt()
    }

    private fun gapOf(gap: Int): Int = Math.min(Math.max(gap, 0), gapCap())

    private fun clampR(value: Int): Int = Math.max(0, Math.min(value, dimR - 1))

    private fun curX(): Int = curAttrId * dimR + if (frozen) clampR(refreshLeft) else 0

    /**
     * 选出下一击使用的武器下标。
     * @param heldIdx 当前手持武器在候选表中的下标
     * @param heldValue 当前冷却剩余伤害额度
     * @param useSkip 是否允许按 skipMargin 跳过这一击（对齐暴击）
     */
    fun pickHit(heldIdx: Int, heldValue: Float, timer: Int, gap: Int, useSkip: Boolean): Int {
        val clampedTimer = Math.min(Math.max(timer, 0), interval)
        val clampedGap = gapOf(gap)
        val cooled = clampedTimer >= interval
        val canAttack = open(clampedGap)
        val valueIdx = if (cooled) valCount else valId(heldValue)
        val nextTimer = Math.min(clampedTimer + 1, interval)
        var pick = -1
        val wantSkip = useSkip && allowSkip && cooled && canAttack
        val state = curX()
        val skipScore = stage(1, nextTimer, valueIdx, nextGap(clampedGap), state)
        var best = if (!useSkip) skipScore.toDouble() else Double.NEGATIVE_INFINITY
        predicted = 0.0f
        accepted = false
        val currentAttr = attrs[curAttrId]
        val crit = critAt(0)

        for (w in 0 until n) {
            val amount = amount(currentAttr, crit, bonus[w])
            var cost: Int
            val score: Int
            if (canAttack && (cooled || amount > heldValue)) {
                cost = if (cooled) loss(amount) else loss(amount - heldValue)
                val nextState = if (cooled) curAttrId * dimR + dimR - 1 else state
                score = cost * WEIGHT[0] + stage(1, if (cooled) 1 else nextTimer, valId(amount), nextGap(0), nextState)
            } else {
                cost = 0
                score = stage(1, nextTimer, valueIdx, nextGap(clampedGap), state)
            }

            var mixed = score
            if (canAttack && isEdge(clampedTimer)) {
                mixed = edgeMix(mixed, 0, curAttrId, amount)
            }

            val weighted = mixed.toDouble()
                - (if (w != heldIdx) 0.4 else 0.0)
                - (if (sword[w]) 0.0 else 0.2)
                + (if (cost > 0) 0.3 + Math.min(amount, 99.0f) * 1.0E-4 else 0.0)
                - (if (canAttack || (!cooled && !(amount > heldValue))) 0.0 else 0.1 + Math.min(amount, 99.0f) * 0.001)
            if (weighted > best) {
                best = weighted
                pick = w
                predicted = amount
                accepted = cost > 0
            }
        }

        skip = false
        if (wantSkip && pick >= 0 && skipScore > best * (1.0 + skipMargin)) {
            skip = true
            predicted = 0.0f
            accepted = false
            return -1
        }

        return pick
    }

    /** 冻结/换武器场景：选出下一把应持有的武器下标；refresh 表示需要刷新属性。 */
    fun pickStage(heldIdx: Int, heldValue: Float, timer: Int, gap: Int): Int {
        refresh = false
        if (srcCount == 0 || frozen && refreshLeft <= 0) {
            return -1
        }

        val clampedTimer = Math.min(Math.max(timer, 0), interval)
        val valueIdx = if (clampedTimer >= interval) valCount else valId(heldValue)
        val clampedGap = gapOf(gap)
        val hasSword = sword.copyOf(n).any { it }

        var pick = -1
        var best = Double.NEGATIVE_INFINITY

        for (w in 0 until n) {
            if (!hasSword || sword[w]) {
                val id = attrId(attr[w], false)
                val state = when {
                    !frozen -> id * dimR
                    id == curAttrId -> curX()
                    else -> id * dimR + clampR(refreshLeft) - 1
                }

                val weighted = value(0, state, clampedTimer, valueIdx, clampedGap).toDouble()
                    - (if (w != heldIdx) 0.4 else 0.0)
                    + Math.min(attr[w], 99.0f) * 0.001
                    - (if (frozen && id != curAttrId) 0.5 else 0.0)
                if (weighted > best) {
                    best = weighted
                    pick = w
                }
            }
        }

        if (frozen) {
            if (pick < 0 || attrId(attr[pick], false) == curAttrId) {
                return -1
            }

            refresh = true
        }

        return pick
    }

    companion object {
        private const val CRIT_RUN = 3
        private val WEIGHT = IntArray(64)

        fun amount(attr: Float, crit: Boolean, bonus: Float): Float =
            (if (crit && attr > 0.0f) attr * 1.5f else attr) + bonus

        init {
            var weight = 10000.0
            for (i in WEIGHT.indices) {
                WEIGHT[i] = Math.round(weight).toInt()
                weight *= 0.95
            }
        }
    }
}
