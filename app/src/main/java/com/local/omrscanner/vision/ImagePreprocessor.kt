package com.local.omrscanner.vision

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 图像多通道预处理模块：
 * 统一提供定位专用通道（CLAHE 局部对比度均衡 + Otsu 全局阈值 + Adaptive 局部阈值双路二值图）
 * 与 OMR 识别专用色彩通道
 */
object ImagePreprocessor {

    data class PreprocessResult(
        val scaledBitmap: Bitmap,
        val width: Int,
        val height: Int,
        val scaleFactor: Float,
        val grayPixels: IntArray,
        val otsuBinary: BooleanArray,
        val adaptiveBinary: BooleanArray,
        val isRedInkDetected: Boolean
    )

    /**
     * 对输入原始图像执行缩放、灰度化、对比度增强与互补双阈值二值化
     */
    fun preprocess(sourceBitmap: Bitmap): PreprocessResult {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val maxDim = max(srcW, srcH)

        val targetMaxDim = DetectionConfig.WORK_IMAGE_LONG_EDGE
        val scale = if (maxDim > targetMaxDim) {
            targetMaxDim.toFloat() / maxDim.toFloat()
        } else {
            1.0f
        }

        val workW = (srcW * scale).toInt()
        val workH = (srcH * scale).toInt()

        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(sourceBitmap, workW, workH, true)
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val totalPixels = workW * workH
        val srcPixels = IntArray(totalPixels)
        scaledBitmap.getPixels(srcPixels, 0, workW, 0, 0, workW, workH)

        val gray = IntArray(totalPixels)
        var redInkPixels = 0

        // 提取灰度与色彩分析
        for (i in 0 until totalPixels) {
            val c = srcPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val gbAvg = (g + b) / 2f
            val isRed = (r > 80) && ((r / max(1f, gbAvg)) > 1.25f) && ((r - g) > 16) && ((r - b) > 16)
            if (isRed) redInkPixels++

            // 定位通道亮度提取：加权灰度
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            gray[i] = lum
        }

        val isRedInk = (redInkPixels.toFloat() / max(1, totalPixels)) > 0.005f

        // 受控对比度局部直方图均衡化 (CLAHE, 8x8 网格, 局部对比度提升)
        val enhancedGray = applyClahe(gray, workW, workH, gridSize = 8, clipLimit = 2.5f)

        // 生成 Otsu 全局阈值二值图
        val otsuThresh = computeOtsuThreshold(enhancedGray)
        val otsuBinary = BooleanArray(totalPixels)
        for (i in 0 until totalPixels) {
            otsuBinary[i] = enhancedGray[i] < otsuThresh
        }

        // 生成 Bradley-Roth 局部自适应二值图 (积分图加速)
        val adaptiveBinary = computeAdaptiveThreshold(enhancedGray, workW, workH)

        return PreprocessResult(
            scaledBitmap = scaledBitmap,
            width = workW,
            height = workH,
            scaleFactor = scale,
            grayPixels = enhancedGray,
            otsuBinary = otsuBinary,
            adaptiveBinary = adaptiveBinary,
            isRedInkDetected = isRedInk
        )
    }

    /**
     * 计算 Otsu 全局最佳二值化阈值
     */
    private fun computeOtsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) {
            hist[v.coerceIn(0, 255)]++
        }

        val total = gray.size.toFloat()
        var sum = 0f
        for (i in 0..255) sum += i * hist[i]

        var sumB = 0f
        var wB = 0
        var maxVariance = 0f
        var bestThresh = 128

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = gray.size - wB
            if (wF == 0) break

            sumB += t.toFloat() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val variance = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                bestThresh = t
            }
        }
        return bestThresh
    }

    /**
     * Bradley-Roth 积分图局部自适应阈值
     */
    private fun computeAdaptiveThreshold(gray: IntArray, w: Int, h: Int): BooleanArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            val rowOffset = y * w
            val intRowOffset = (y + 1) * (w + 1)
            val prevIntRowOffset = y * (w + 1)
            for (x in 0 until w) {
                val lum = gray[rowOffset + x]
                rowSum += lum
                integral[intRowOffset + x + 1] = integral[prevIntRowOffset + x + 1] + rowSum
            }
        }

        val binary = BooleanArray(w * h)
        val radius = max(12, min(w, h) / 28)
        val tFactor = 85L

        for (y in 0 until h) {
            val y1 = max(0, y - radius)
            val y2 = min(h - 1, y + radius)
            val rowOffset = y * w
            for (x in 0 until w) {
                val x1 = max(0, x - radius)
                val x2 = min(w - 1, x + radius)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * (w + 1) + (x2 + 1)] -
                        integral[y1 * (w + 1) + (x2 + 1)] -
                        integral[(y2 + 1) * (w + 1) + x1] +
                        integral[y1 * (w + 1) + x1]
                val lum = gray[rowOffset + x]
                binary[rowOffset + x] = (lum * 100L) < (sum * tFactor / count)
            }
        }
        return binary
    }

    /**
     * 简易受控对比度局部直方图均衡化 (CLAHE, 抑制复印噪点并提升反差)
     */
    private fun applyClahe(gray: IntArray, w: Int, h: Int, gridSize: Int, clipLimit: Float): IntArray {
        val tileW = w / gridSize
        val tileH = h / gridSize
        if (tileW < 8 || tileH < 8) return gray

        val output = IntArray(gray.size)
        val clipVal = (tileW * tileH * clipLimit / 256f).toInt()

        for (gy in 0 until gridSize) {
            val yStart = gy * tileH
            val yEnd = if (gy == gridSize - 1) h else (gy + 1) * tileH
            for (gx in 0 until gridSize) {
                val xStart = gx * tileW
                val xEnd = if (gx == gridSize - 1) w else (gx + 1) * tileW

                val hist = IntArray(256)
                for (y in yStart until yEnd) {
                    val ro = y * w
                    for (x in xStart until xEnd) {
                        hist[gray[ro + x].coerceIn(0, 255)]++
                    }
                }

                // 限制直方图超额
                var excess = 0
                for (i in 0..255) {
                    if (hist[i] > clipVal) {
                        excess += hist[i] - clipVal
                        hist[i] = clipVal
                    }
                }
                val bonus = excess / 256
                for (i in 0..255) hist[i] += bonus

                // 累计分布 CDF
                val cdf = FloatArray(256)
                var acc = 0
                val totalInTile = (xEnd - xStart) * (yEnd - yStart)
                for (i in 0..255) {
                    acc += hist[i]
                    cdf[i] = (acc.toFloat() / totalInTile) * 255f
                }

                for (y in yStart until yEnd) {
                    val ro = y * w
                    for (x in xStart until xEnd) {
                        output[ro + x] = cdf[gray[ro + x].coerceIn(0, 255)].toInt().coerceIn(0, 255)
                    }
                }
            }
        }
        return output
    }
}
