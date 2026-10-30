package com.local.omrscanner.vision

import com.local.omrscanner.model.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 候选黑色定位块特征与质量评分数据结构
 */
data class MarkerCandidate(
    val center: Point2D,
    val width: Float,
    val height: Float,
    val area: Int,
    val areaRatio: Float,
    val aspectRatio: Float,
    val solidity: Float,
    val innerDensity: Float,
    val collarContrast: Float,
    val qualityScore: Float,
    val quadrantIndex: Int // 0: TL, 1: TR, 2: BR, 3: BL
)

/**
 * 多维特征定位块候选检测器：
 * 结合连通域提取与 7 项几何特征打分，绝不仅取面积最大前四个，保障强抗干扰能力
 */
object MarkerDetector {

    fun detectCandidates(
        otsuBinary: BooleanArray,
        adaptBinary: BooleanArray,
        w: Int,
        h: Int
    ): List<MarkerCandidate> {
        val totalArea = (w * h).toFloat()
        val minArea = (totalArea * DetectionConfig.MARKER_MIN_AREA_RATIO).toInt().coerceAtLeast(16)
        val maxArea = (totalArea * DetectionConfig.MARKER_MAX_AREA_RATIO).toInt().coerceAtLeast(minArea * 4)

        // 预建积分图供快速白领对比度计算
        val integral = computeIntegral(adaptBinary, w, h)

        val candidates = mutableListOf<MarkerCandidate>()

        // 分别从 Otsu 全局阈值和 Adaptive 局部阈值二值图中提取连通域候选
        val otsuCandidates = extractCandidatesFromBinary(otsuBinary, w, h, minArea, maxArea, integral)
        val adaptCandidates = extractCandidatesFromBinary(adaptBinary, w, h, minArea, maxArea, integral)

        // 合并并按空间距离去重
        val merged = mutableListOf<MarkerCandidate>()
        merged.addAll(otsuCandidates)

        for (ac in adaptCandidates) {
            val duplicate = merged.any { mc ->
                val dist = sqrt((mc.center.x - ac.center.x) * (mc.center.x - ac.center.x) +
                        (mc.center.y - ac.center.y) * (mc.center.y - ac.center.y))
                dist < max(mc.width, mc.height) * 0.6f
            }
            if (!duplicate) {
                merged.add(ac)
            }
        }

        // 按象限聚类并排序，每个象限最多保留前 N 个高质量候选
        val midX = w / 2f
        val midY = h / 2f
        val quadrantBuckets = Array(4) { mutableListOf<MarkerCandidate>() }

        for (cand in merged) {
            val qIdx = when {
                cand.center.x < midX && cand.center.y < midY -> 0 // TL
                cand.center.x >= midX && cand.center.y < midY -> 1 // TR
                cand.center.x >= midX && cand.center.y >= midY -> 2 // BR
                else -> 3 // BL
            }
            quadrantBuckets[qIdx].add(cand.copy(quadrantIndex = qIdx))
        }

        val result = mutableListOf<MarkerCandidate>()
        for (q in 0..3) {
            val sorted = quadrantBuckets[q].sortedByDescending { it.qualityScore }
            result.addAll(sorted.take(DetectionConfig.MAX_CANDIDATES_PER_QUADRANT))
        }

        return result
    }

    private fun extractCandidatesFromBinary(
        binary: BooleanArray,
        w: Int,
        h: Int,
        minArea: Int,
        maxArea: Int,
        integral: IntArray
    ): List<MarkerCandidate> {
        val visited = BooleanArray(w * h)
        val candidates = mutableListOf<MarkerCandidate>()
        val totalArea = (w * h).toFloat()

        val qx = IntArray(maxArea * 2 + 1024)
        val qy = IntArray(maxArea * 2 + 1024)

        // 仅在图像四周边缘 30% 区域搜索定位块（定位块必在四周）
        val marginX = (w * 0.32f).toInt()
        val marginY = (h * 0.32f).toInt()

        for (y in 2 until h - 2) {
            val inYMargin = y < marginY || y > h - marginY
            for (x in 2 until w - 2) {
                val inXMargin = x < marginX || x > w - marginX
                if (!inXMargin && !inYMargin) continue

                val idx = y * w + x
                if (!binary[idx] || visited[idx]) continue

                // 宽度优先搜索连通域
                var head = 0
                var tail = 0
                qx[tail] = x
                qy[tail] = y
                tail++
                visited[idx] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0

                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head]
                    head++
                    area++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (area > maxArea) break

                    // 4 邻域拓展
                    val neighbors = intArrayOf(
                        cy * w + cx - 1, cx - 1, cy,
                        cy * w + cx + 1, cx + 1, cy,
                        (cy - 1) * w + cx, cx, cy - 1,
                        (cy + 1) * w + cx, cx, cy + 1
                    )
                    for (k in 0 until 4) {
                        val nIdx = neighbors[k * 3]
                        val nx = neighbors[k * 3 + 1]
                        val ny = neighbors[k * 3 + 2]
                        if (nx in 0 until w && ny in 0 until h && !visited[nIdx] && binary[nIdx]) {
                            visited[nIdx] = true
                            if (tail < qx.size) {
                                qx[tail] = nx
                                qy[tail] = ny
                                tail++
                            }
                        }
                    }
                }

                if (area in minArea..maxArea) {
                    val boxW = maxX - minX + 1
                    val boxH = maxY - minY + 1
                    val minDim = min(boxW, boxH).toFloat()
                    val maxDim = max(boxW, boxH).toFloat()
                    val aspectRatio = minDim / maxDim

                    if (aspectRatio >= DetectionConfig.MARKER_MIN_ASPECT_RATIO) {
                        val solidity = area.toFloat() / (boxW * boxH).toFloat()
                        if (solidity >= DetectionConfig.MARKER_MIN_SOLIDITY) {
                            val centerX = (minX + maxX) / 2f
                            val centerY = (minY + maxY) / 2f

                            // 计算内部密度与外围白领对比度
                            val pad = max(4, (minDim / 3).toInt())
                            val sz = minDim.toInt()
                            val innerDark = queryIntegral(integral, w, h, (centerX - sz / 2).toInt(), (centerY - sz / 2).toInt(), (centerX + sz / 2).toInt(), (centerY + sz / 2).toInt())
                            val innerArea = (sz * sz).toFloat()
                            val innerDensity = innerDark / max(1f, innerArea)

                            val collarDark = queryIntegral(integral, w, h, (centerX - sz / 2 - pad).toInt(), (centerY - sz / 2 - pad).toInt(), (centerX + sz / 2 + pad).toInt(), (centerY + sz / 2 + pad).toInt()) - innerDark
                            val collarArea = ((sz + 2 * pad) * (sz + 2 * pad) - innerArea).toFloat()
                            val outerDensity = collarDark / max(1f, collarArea)

                            val collarContrast = innerDensity - 1.5f * outerDensity

                            if (innerDensity >= DetectionConfig.MARKER_MIN_INNER_DENSITY &&
                                collarContrast >= DetectionConfig.MARKER_MIN_COLLAR_CONTRAST) {

                                // 计算候选块综合质量分
                                val score = (aspectRatio * 0.25f) +
                                        (solidity * 0.25f) +
                                        (innerDensity * 0.20f) +
                                        (collarContrast.coerceIn(0f, 1f) * 0.30f)

                                candidates.add(
                                    MarkerCandidate(
                                        center = Point2D(centerX, centerY),
                                        width = boxW.toFloat(),
                                        height = boxH.toFloat(),
                                        area = area,
                                        areaRatio = area / totalArea,
                                        aspectRatio = aspectRatio,
                                        solidity = solidity,
                                        innerDensity = innerDensity,
                                        collarContrast = collarContrast,
                                        qualityScore = score,
                                        quadrantIndex = 0
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return candidates
    }

    private fun computeIntegral(binary: BooleanArray, w: Int, h: Int): IntArray {
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            val rowOffset = y * w
            val intRowOffset = (y + 1) * (w + 1)
            val prevIntRowOffset = y * (w + 1)
            for (x in 0 until w) {
                if (binary[rowOffset + x]) rowSum++
                integral[intRowOffset + x + 1] = integral[prevIntRowOffset + x + 1] + rowSum
            }
        }
        return integral
    }

    private fun queryIntegral(integral: IntArray, w: Int, h: Int, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val cx1 = x1.coerceIn(0, w)
        val cy1 = y1.coerceIn(0, h)
        val cx2 = x2.coerceIn(0, w)
        val cy2 = y2.coerceIn(0, h)
        if (cx2 <= cx1 || cy2 <= cy1) return 0
        return integral[cy2 * (w + 1) + cx2] -
                integral[cy1 * (w + 1) + cx2] -
                integral[cy2 * (w + 1) + cx1] +
                integral[cy1 * (w + 1) + cx1]
    }
}
