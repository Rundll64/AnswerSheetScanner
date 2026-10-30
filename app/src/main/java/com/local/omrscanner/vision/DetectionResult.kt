package com.local.omrscanner.vision

import android.graphics.Bitmap
import com.local.omrscanner.model.Point2D

/**
 * 定位质量置信度级别
 */
enum class ConfidenceLevel {
    /** 高置信度 (>= 0.78)：四角极佳对齐，重投影误差极低，自动输出正视图进入阅卷 */
    HIGH,

    /** 中置信度 (0.48 ~ 0.78)：定位基本可靠，提示用户确认或微调 */
    MEDIUM,

    /** 低置信度 (< 0.48)：定位疑似偏差、遮挡或出界，拦截自动判分，无缝分流至手动微调界面 */
    LOW
}

/**
 * 智能答题卡检测与透视矫正标准化输出结果
 */
data class DetectionResult(
    /** 是否定位成功 */
    val success: Boolean,

    /** 映射回原图的四角中心坐标 (顺序: TopLeft, TopRight, BottomRight, BottomLeft) */
    val corners: List<Point2D>,

    /** 综合置信度得分 [0.0f, 1.0f] */
    val confidence: Float,

    /** 三级置信度枚举 */
    val confidenceLevel: ConfidenceLevel,

    /** 实际检出并参与几何变换锁定的有效定位块数量 (0..4) */
    val markerCount: Int,

    /** 归一化平均重投影误差 (像素) */
    val reprojectionErrorPx: Double,

    /** 透视校正后的标准 1200 x 1700 画布 Bitmap (若失败则为 null) */
    val warpedBitmap: Bitmap?,

    /** 失败原因 (若成功则为 null) */
    val failureReason: String? = null
)
