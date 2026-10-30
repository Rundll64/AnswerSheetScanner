package com.local.omrscanner.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.local.omrscanner.model.Point2D
import com.local.omrscanner.omr.TemplateSpec
import kotlin.math.abs

/**
 * 几何变换与单应性透视矫正估计器：
 * 采用 8 自由度 Homography 高斯消元求解与双线性插值（Bilinear Interpolation）
 * 将拍摄倾斜图像高精度拉平为 1200 x 1700 标准正视图
 */
object CardGeometryEstimator {

    /**
     * 求解从标准画布坐标 dstPts 到原图坐标 srcPts 的反向单应性矩阵 H
     * (用于后向映射双线性插值采样)
     */
    fun solveHomography(fromDst: List<Point2D>, toSrc: List<Point2D>): DoubleArray? {
        if (fromDst.size < 4 || toSrc.size < 4) return null

        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = fromDst[i].x.toDouble()
            val y = fromDst[i].y.toDouble()
            val u = toSrc[i].x.toDouble()
            val v = toSrc[i].y.toDouble()

            a[2 * i][0] = x
            a[2 * i][1] = y
            a[2 * i][2] = 1.0
            a[2 * i][3] = 0.0
            a[2 * i][4] = 0.0
            a[2 * i][5] = 0.0
            a[2 * i][6] = -x * u
            a[2 * i][7] = -y * u
            a[2 * i][8] = u

            a[2 * i + 1][0] = 0.0
            a[2 * i + 1][1] = 0.0
            a[2 * i + 1][2] = 0.0
            a[2 * i + 1][3] = x
            a[2 * i + 1][4] = y
            a[2 * i + 1][5] = 1.0
            a[2 * i + 1][6] = -x * v
            a[2 * i + 1][7] = -y * v
            a[2 * i + 1][8] = v
        }

        // 高斯全主元消元法
        for (col in 0 until 8) {
            var pivotRow = col
            var maxVal = abs(a[col][col])
            for (r in col + 1 until 8) {
                val v = abs(a[r][col])
                if (v > maxVal) {
                    maxVal = v
                    pivotRow = r
                }
            }

            if (pivotRow != col) {
                val temp = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = temp
            }

            val pivot = a[col][col]
            if (abs(pivot) < 1e-9) return null

            for (j in col..8) {
                a[col][j] /= pivot
            }

            for (row in 0 until 8) {
                if (row != col) {
                    val factor = a[row][col]
                    for (j in col..8) {
                        a[row][j] -= factor * a[col][j]
                    }
                }
            }
        }

        return DoubleArray(8) { idx -> a[idx][8] }
    }

    /**
     * 将原图透视校正为标准 canonical 尺寸 (默认 1200 x 1700)，采用双线性插值
     */
    fun warpToCanonical(
        srcBitmap: Bitmap,
        srcCorners: List<Point2D>,
        dstW: Int = TemplateSpec.CANONICAL_WIDTH,
        dstH: Int = TemplateSpec.CANONICAL_HEIGHT
    ): Bitmap {
        val dstCorners = TemplateSpec.CANONICAL_MARKERS
        val hMat = solveHomography(dstCorners, srcCorners)
            ?: return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)

        val srcW = srcBitmap.width
        val srcH = srcBitmap.height
        val srcPixels = IntArray(srcW * srcH)
        srcBitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(dstW * dstH)

        for (y in 0 until dstH) {
            val yf = y.toDouble()
            val rowOffset = y * dstW
            for (x in 0 until dstW) {
                val xf = x.toDouble()
                val denom = hMat[6] * xf + hMat[7] * yf + 1.0

                if (abs(denom) > 1e-7) {
                    val realX = (hMat[0] * xf + hMat[1] * yf + hMat[2]) / denom
                    val realY = (hMat[3] * xf + hMat[4] * yf + hMat[5]) / denom

                    if (realX >= 0.0 && realX < (srcW - 1).toDouble() &&
                        realY >= 0.0 && realY < (srcH - 1).toDouble()
                    ) {
                        // 双线性插值 (Bilinear Interpolation)
                        val x0 = realX.toInt()
                        val y0 = realY.toInt()
                        val x1 = x0 + 1
                        val y1 = y0 + 1
                        val wx = (realX - x0).toFloat()
                        val wy = (realY - y0).toFloat()

                        val c00 = srcPixels[y0 * srcW + x0]
                        val c10 = srcPixels[y0 * srcW + x1]
                        val c01 = srcPixels[y1 * srcW + x0]
                        val c11 = srcPixels[y1 * srcW + x1]

                        val r = bilerp((c00 shr 16) and 0xFF, (c10 shr 16) and 0xFF, (c01 shr 16) and 0xFF, (c11 shr 16) and 0xFF, wx, wy)
                        val g = bilerp((c00 shr 8) and 0xFF, (c10 shr 8) and 0xFF, (c01 shr 8) and 0xFF, (c11 shr 8) and 0xFF, wx, wy)
                        val b = bilerp(c00 and 0xFF, c10 and 0xFF, c01 and 0xFF, c11 and 0xFF, wx, wy)

                        dstPixels[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        dstPixels[rowOffset + x] = Color.WHITE
                    }
                } else {
                    dstPixels[rowOffset + x] = Color.WHITE
                }
            }
        }

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        out.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return out
    }

    private inline fun bilerp(c00: Int, c10: Int, c01: Int, c11: Int, wx: Float, wy: Float): Int {
        val top = c00 * (1.0f - wx) + c10 * wx
        val bottom = c01 * (1.0f - wx) + c11 * wx
        return (top * (1.0f - wy) + bottom * wy).toInt().coerceIn(0, 255)
    }
}
