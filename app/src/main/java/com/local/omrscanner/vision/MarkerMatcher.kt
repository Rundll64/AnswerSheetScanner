package com.local.omrscanner.vision

import com.local.omrscanner.model.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MatchResult(
    val corners: List<Point2D>, // 顺序: TL, TR, BR, BL
    val score: Float,
    val markerCount: Int,
    val missingQuadrant: Int? = null // 0: TL, 1: TR, 2: BR, 3: BL, null: 无缺失
)

/**
 * 候选组合几何匹配与剪枝器：
 * 从候选池中枚举凸四边形组合，结合模板长宽比、尺寸连续性与加权几何评分寻优
 */
object MarkerMatcher {

    private const val CANONICAL_ASPECT_RATIO = 1200f / 1700f // ≈ 0.70588f

    fun findBestMatch(candidates: List<MarkerCandidate>, imageW: Int, imageH: Int): MatchResult? {
        val q0 = candidates.filter { it.quadrantIndex == 0 } // TL
        val q1 = candidates.filter { it.quadrantIndex == 1 } // TR
        val q2 = candidates.filter { it.quadrantIndex == 2 } // BR
        val q3 = candidates.filter { it.quadrantIndex == 3 } // BL

        var bestScore = -1f
        var bestMatch: MatchResult? = null

        // 优先尝试 4 点完整匹配
        if (q0.isNotEmpty() && q1.isNotEmpty() && q2.isNotEmpty() && q3.isNotEmpty()) {
            for (c0 in q0) {
                for (c1 in q1) {
                    // 尺寸连续性剪枝
                    if (!isSizeConsistent(c0, c1)) continue
                    for (c2 in q2) {
                        if (!isSizeConsistent(c1, c2)) continue
                        for (c3 in q3) {
                            if (!isSizeConsistent(c2, c3) || !isSizeConsistent(c3, c0)) continue

                            val pts = listOf(c0.center, c1.center, c2.center, c3.center)
                            if (!isStrictConvexQuad(pts)) continue

                            val score = evaluateQuadScore(pts, listOf(c0, c1, c2, c3))
                            if (score > bestScore) {
                                bestScore = score
                                bestMatch = MatchResult(
                                    corners = pts,
                                    score = score,
                                    markerCount = 4,
                                    missingQuadrant = null
                                )
                            }
                        }
                    }
                }
            }
        }

        // 若 4 点匹配得分较高，直接返回
        if (bestMatch != null && bestMatch.score >= 0.60f) {
            return bestMatch
        }

        // 降级尝试 3 点匹配（支持单角出界/遮挡场景）
        val quadrants = listOf(q0, q1, q2, q3)
        for (missingQ in 0..3) {
            val available = (0..3).filter { it != missingQ }
            val listA = quadrants[available[0]]
            val listB = quadrants[available[1]]
            val listC = quadrants[available[2]]

            if (listA.isEmpty() || listB.isEmpty() || listC.isEmpty()) continue

            for (cA in listA) {
                for (cB in listB) {
                    if (!isSizeConsistent(cA, cB)) continue
                    for (cC in listC) {
                        if (!isSizeConsistent(cB, cC)) continue

                        // 根据已知的 3 个角点估算缺失的第 4 角点
                        val estimatedCorners = estimateFourthCorner(cA, cB, cC, available[0], available[1], available[2], missingQ)
                        if (!isStrictConvexQuad(estimatedCorners)) continue

                        val avgContrast = (cA.qualityScore + cB.qualityScore + cC.qualityScore) / 3f
                        val score = evaluateTriScore(estimatedCorners, avgContrast) * 0.85f // 3点模式轻微降权

                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = MatchResult(
                                corners = estimatedCorners,
                                score = score,
                                markerCount = 3,
                                missingQuadrant = missingQ
                            )
                        }
                    }
                }
            }
        }

        return bestMatch
    }

    private fun isSizeConsistent(c1: MarkerCandidate, c2: MarkerCandidate): Boolean {
        val s1 = sqrt(c1.area.toFloat())
        val s2 = sqrt(c2.area.toFloat())
        val ratio = s1 / max(1f, s2)
        return ratio in 0.60f..1.65f
    }

    /**
     * 严格凸四边形校验（顺时针叉积必须同号，且内角在合理范围）
     */
    private fun isStrictConvexQuad(pts: List<Point2D>): Boolean {
        if (pts.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            val p3 = pts[(i + 2) % 4]
            val cross = (p2.x - p1.x) * (p3.y - p2.y) - (p2.y - p1.y) * (p3.x - p2.x)
            if (abs(cross) < 1e-4) return false
            val currentSign = if (cross > 0) 1 else -1
            if (sign == 0) {
                sign = currentSign
            } else if (sign != currentSign) {
                return false
            }
        }
        return true
    }

    /**
     * 加权几何评分函数
     */
    private fun evaluateQuadScore(pts: List<Point2D>, cands: List<MarkerCandidate>): Float {
        val tl = pts[0]
        val tr = pts[1]
        val br = pts[2]
        val bl = pts[3]

        // 1. 形状平行与收敛性得分 S_shape
        val topW = dist(tl, tr)
        val bottomW = dist(bl, br)
        val leftH = dist(tl, bl)
        val rightH = dist(tr, br)

        val wRatio = min(topW, bottomW) / max(1f, max(topW, bottomW))
        val hRatio = min(leftH, rightH) / max(1f, max(leftH, rightH))
        val sShape = (wRatio + hRatio) / 2f

        // 2. 长宽比一致性得分 S_ratio
        val avgW = (topW + bottomW) / 2f
        val avgH = (leftH + rightH) / 2f
        val observedRatio = avgW / max(1f, avgH)
        val ratioDev = abs(observedRatio - CANONICAL_ASPECT_RATIO) / CANONICAL_ASPECT_RATIO
        val sRatio = (1.0f - ratioDev * 2.5f).coerceIn(0f, 1f)

        // 3. 对角线交叉合理性 S_template
        val diag1 = dist(tl, br)
        val diag2 = dist(tr, bl)
        val diagRatio = min(diag1, diag2) / max(1f, max(diag1, diag2))
        val sTemplate = diagRatio.coerceIn(0f, 1f)

        // 4. 四候选自身对比度均值 S_contrast
        val sContrast = cands.map { it.qualityScore }.average().toFloat().coerceIn(0f, 1f)

        return DetectionConfig.WEIGHT_SHAPE * sShape +
                DetectionConfig.WEIGHT_RATIO * sRatio +
                DetectionConfig.WEIGHT_TEMPLATE * sTemplate +
                DetectionConfig.WEIGHT_CONTRAST * sContrast
    }

    private fun evaluateTriScore(pts: List<Point2D>, avgContrast: Float): Float {
        val tl = pts[0]
        val tr = pts[1]
        val br = pts[2]
        val bl = pts[3]

        val topW = dist(tl, tr)
        val bottomW = dist(bl, br)
        val leftH = dist(tl, bl)
        val rightH = dist(tr, br)

        val avgW = (topW + bottomW) / 2f
        val avgH = (leftH + rightH) / 2f
        val observedRatio = avgW / max(1f, avgH)
        val ratioDev = abs(observedRatio - CANONICAL_ASPECT_RATIO) / CANONICAL_ASPECT_RATIO
        val sRatio = (1.0f - ratioDev * 2.5f).coerceIn(0f, 1f)

        return 0.5f * sRatio + 0.5f * avgContrast
    }

    private fun estimateFourthCorner(
        cA: MarkerCandidate, cB: MarkerCandidate, cC: MarkerCandidate,
        qA: Int, qB: Int, qC: Int, missingQ: Int
    ): List<Point2D> {
        val map = mutableMapOf(qA to cA.center, qB to cB.center, qC to cC.center)

        // 平行四边形向量推断缺失角点
        val pMissing = when (missingQ) {
            0 -> Point2D(map[1]!!.x + map[3]!!.x - map[2]!!.x, map[1]!!.y + map[3]!!.y - map[2]!!.y) // TL = TR + BL - BR
            1 -> Point2D(map[0]!!.x + map[2]!!.x - map[3]!!.x, map[0]!!.y + map[2]!!.y - map[3]!!.y) // TR = TL + BR - BL
            2 -> Point2D(map[1]!!.x + map[3]!!.x - map[0]!!.x, map[1]!!.y + map[3]!!.y - map[0]!!.y) // BR = TR + BL - TL
            else -> Point2D(map[0]!!.x + map[2]!!.x - map[1]!!.x, map[0]!!.y + map[2]!!.y - map[1]!!.y) // BL = TL + BR - TR
        }
        map[missingQ] = pMissing

        return listOf(map[0]!!, map[1]!!, map[2]!!, map[3]!!)
    }

    private fun dist(p1: Point2D, p2: Point2D): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
}
