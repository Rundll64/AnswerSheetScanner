package com.local.omrscanner.vision

import android.graphics.Bitmap
import com.local.omrscanner.model.Point2D

/**
 * 智能答题卡检测与透视校正总入口引擎 (CardDetectionEngine)：
 * 串联统一多通道预处理、候选提取、凸四边形匹配、Homography 估计与三级置信度验证
 */
object CardDetectionEngine {

    /**
     * 对拍摄或导入的原始答题卡图像执行全自动四角智能检测、透视矫正与质量评估
     */
    fun detectAndWarp(sourceBitmap: Bitmap): DetectionResult {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        // 阶段 A：统一多通道预处理 (CLAHE + Otsu/Adaptive 双阈值)
        val prep = ImagePreprocessor.preprocess(sourceBitmap)

        // 阶段 B：候选黑块多特征提取
        val candidates = MarkerDetector.detectCandidates(
            otsuBinary = prep.otsuBinary,
            adaptBinary = prep.adaptiveBinary,
            w = prep.width,
            h = prep.height
        )

        // 阶段 C：凸四边形匹配与模板长宽比寻优
        val match = MarkerMatcher.findBestMatch(candidates, prep.width, prep.height)

        if (match == null) {
            // 定位失败，返回默认四角并标记 LOW 置信度，无缝对接手动微调界面
            val defaultCorners = getDefaultCorners(srcW, srcH)
            return DetectionResult(
                success = false,
                corners = defaultCorners,
                confidence = 0.20f,
                confidenceLevel = ConfidenceLevel.LOW,
                markerCount = 0,
                reprojectionErrorPx = 25.0,
                warpedBitmap = null,
                failureReason = "未找到足够的答题卡四角定位标记"
            )
        }

        // 将工作图坐标按缩放比例映射回原图坐标
        val invScale = 1.0f / prep.scaleFactor
        val srcCorners = match.corners.map { pt ->
            Point2D(pt.x * invScale, pt.y * invScale)
        }

        // 阶段 D：单应性透视变换拉平为 1200 x 1700 正视图 (双线性插值)
        val warpedBitmap = CardGeometryEstimator.warpToCanonical(
            srcBitmap = sourceBitmap,
            srcCorners = srcCorners
        )

        // 阶段 E：定位质量把关与三级置信度评估
        val validation = DetectionValidator.validate(
            warpedBitmap = warpedBitmap,
            detectedCorners = srcCorners,
            sourceW = srcW,
            sourceH = srcH,
            markerCount = match.markerCount
        )

        return DetectionResult(
            success = validation.confidenceLevel != ConfidenceLevel.LOW,
            corners = srcCorners,
            confidence = validation.confidence,
            confidenceLevel = validation.confidenceLevel,
            markerCount = match.markerCount,
            reprojectionErrorPx = validation.reprojectionErrorPx,
            warpedBitmap = if (validation.confidenceLevel != ConfidenceLevel.LOW) warpedBitmap else null,
            failureReason = validation.failureReason
        )
    }

    /**
     * 提取用于前台界面展示并支持用户手动拖动微调的四角坐标
     */
    fun autoDetectCorners(sourceBitmap: Bitmap): List<Point2D> {
        val result = detectAndWarp(sourceBitmap)
        return result.corners
    }

    private fun getDefaultCorners(w: Int, h: Int): List<Point2D> {
        val mX = w * 0.05f
        val mY = h * 0.04f
        return listOf(
            Point2D(mX, mY),
            Point2D(w - mX, mY),
            Point2D(w - mX, h - mY),
            Point2D(mX, h - mY)
        )
    }
}
