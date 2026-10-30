package com.local.omrscanner.vision

import android.graphics.Bitmap
import com.local.omrscanner.model.Point2D
import com.local.omrscanner.omr.TemplateSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ValidationResult(
    val confidence: Float,
    val confidenceLevel: ConfidenceLevel,
    val reprojectionErrorPx: Double,
    val isOrientationCorrect: Boolean,
    val failureReason: String? = null
)

/**
 * 定位质量验证与三级置信度把关闸门：
 * 绝不允许扭曲形变或定位错误的图像静默流入后续判卷
 */
object DetectionValidator {

    fun validate(
        warpedBitmap: Bitmap,
        detectedCorners: List<Point2D>,
        sourceW: Int,
        sourceH: Int,
        markerCount: Int
    ): ValidationResult {
        val w = warpedBitmap.width
        val h = warpedBitmap.height

        // 1. C_geometry: 几何合理性
        val cGeometry = evaluateGeometrySanity(detectedCorners, sourceW, sourceH)

        // 2. C_marker: 在校正后的标准图上复查四个定位块坐标处的黑色填充率
        val (cMarker, avgReprojError) = evaluateMarkerPresence(warpedBitmap)

        // 3. C_template: 检查顶部标题横幅带（确保未发生上下颠倒）
        val (cTemplate, isOrientationOk) = evaluateOrientation(warpedBitmap)

        // 4. C_visibility: 四角点是否落在画面有效边界内
        val cVisibility = evaluateVisibility(detectedCorners, sourceW, sourceH)

        // 综合置信度得分 (3 点模式下有轻微乘数惩罚)
        val markerCountMultiplier = if (markerCount == 4) 1.0f else 0.88f
        val rawConfidence = (0.30f * cGeometry + 0.35f * cMarker + 0.20f * cTemplate + 0.15f * cVisibility) * markerCountMultiplier
        val confidence = rawConfidence.coerceIn(0f, 1.0f)

        val level = when {
            confidence >= DetectionConfig.CONFIDENCE_HIGH_THRESHOLD && isOrientationOk -> ConfidenceLevel.HIGH
            confidence >= DetectionConfig.CONFIDENCE_LOW_THRESHOLD -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val reason = when {
            !isOrientationOk -> "答题卡方向可能颠倒或未对齐顶部"
            cGeometry < 0.40f -> "四角定位几何畸变过大"
            cMarker < 0.40f -> "定位标记黑色反差不明显或存在遮挡"
            else -> null
        }

        return ValidationResult(
            confidence = confidence,
            confidenceLevel = level,
            reprojectionErrorPx = avgReprojError,
            isOrientationCorrect = isOrientationOk,
            failureReason = reason
        )
    }

    private fun evaluateGeometrySanity(corners: List<Point2D>, srcW: Int, srcH: Int): Float {
        if (corners.size != 4) return 0f
        val tl = corners[0]
        val tr = corners[1]
        val br = corners[2]
        val bl = corners[3]

        // 检查上下对边比值与左右对边比值
        val topW = dist(tl, tr)
        val botW = dist(bl, br)
        val leftH = dist(tl, bl)
        val rightH = dist(tr, br)

        val wRatio = min(topW, botW) / max(1f, max(topW, botW))
        val hRatio = min(leftH, rightH) / max(1f, max(leftH, rightH))

        // 比例不能小于 0.45（极端倾斜梯形）
        if (wRatio < 0.45f || hRatio < 0.45f) return 0.20f

        return ((wRatio + hRatio) / 2f).coerceIn(0f, 1f)
    }

    private fun evaluateMarkerPresence(warped: Bitmap): Pair<Float, Double> {
        val w = warped.width
        val h = warped.height
        val sz = (TemplateSpec.MARKER_SIZE * 0.75f).toInt()

        var sumFill = 0f
        val pixels = IntArray(w * h)
        warped.getPixels(pixels, 0, w, 0, 0, w, h)

        for (pt in TemplateSpec.CANONICAL_MARKERS) {
            val cx = pt.x.toInt()
            val cy = pt.y.toInt()
            var dark = 0
            var total = 0
            for (dy in -sz / 2..sz / 2) {
                val y = (cy + dy).coerceIn(0, h - 1)
                for (dx in -sz / 2..sz / 2) {
                    val x = (cx + dx).coerceIn(0, w - 1)
                    val c = pixels[y * w + x]
                    val lum = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                    if (lum < 100) dark++
                    total++
                }
            }
            val fill = dark.toFloat() / max(1, total).toFloat()
            sumFill += fill
        }

        val avgMarkerFill = sumFill / 4f
        val reprojErr = ((1.0f - avgMarkerFill) * 8.0).coerceAtLeast(0.5)

        return Pair(avgMarkerFill.coerceIn(0f, 1f), reprojErr)
    }

    private fun evaluateOrientation(warped: Bitmap): Pair<Float, Boolean> {
        val w = warped.width
        val h = warped.height

        // 检查顶部 Y: 30 ~ 120 范围内的文字横幅密度
        var topDark = 0
        var botDark = 0
        val sampleRows = 40
        val pixels = IntArray(w)

        for (y in 50 until 50 + sampleRows) {
            warped.getPixels(pixels, 0, w, 0, y.coerceIn(0, h - 1), w, 1)
            for (x in (w * 0.15f).toInt() until (w * 0.85f).toInt()) {
                val c = pixels[x]
                val lum = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                if (lum < 140) topDark++
            }
        }

        for (y in (h - 120) until (h - 120 + sampleRows)) {
            warped.getPixels(pixels, 0, w, 0, y.coerceIn(0, h - 1), w, 1)
            for (x in (w * 0.15f).toInt() until (w * 0.85f).toInt()) {
                val c = pixels[x]
                val lum = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                if (lum < 140) botDark++
            }
        }

        val isOrientationOk = topDark >= botDark * 0.6f
        val score = if (isOrientationOk) 0.90f else 0.30f
        return Pair(score, isOrientationOk)
    }

    private fun evaluateVisibility(corners: List<Point2D>, srcW: Int, srcH: Int): Float {
        var visibleCount = 0
        for (pt in corners) {
            if (pt.x in 0f..srcW.toFloat() && pt.y in 0f..srcH.toFloat()) {
                visibleCount++
            }
        }
        return visibleCount / 4f
    }

    private fun dist(p1: Point2D, p2: Point2D): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
