package com.local.omrscanner.vision

/**
 * 智能答题卡四角定位与几何验证核心配置参数
 */
object DetectionConfig {
    // 归一化工作区长边尺寸 (加速处理并保持稳定精度)
    const val WORK_IMAGE_LONG_EDGE = 1600

    // 定位块连通域面积占全图比例范围 [0.00015, 0.006]
    const val MARKER_MIN_AREA_RATIO = 0.00015f
    const val MARKER_MAX_AREA_RATIO = 0.00600f

    // 旋转外接矩形宽高比容差 (兼顾 0~35 度透视倾斜压缩)
    const val MARKER_MIN_ASPECT_RATIO = 0.58f
    const val MARKER_MAX_ASPECT_RATIO = 1.00f

    // 矩形填充度 (Solidity / Extent)，排除空心方框与字母
    const val MARKER_MIN_SOLIDITY = 0.70f

    // 凸度 (Convexity = 面积 / 凸包面积)，排除手写与残缺阴影
    const val MARKER_MIN_CONVEXITY = 0.82f

    // 内部黑像素均值密度，排除浅灰或半色调图案
    const val MARKER_MIN_INNER_DENSITY = 0.65f

    // 外围白领对比度 (innerDensity - 1.5 * outerDensity)，排除表格交界与连涂
    const val MARKER_MIN_COLLAR_CONTRAST = 0.42f

    // 每个象限保留的候选黑块最大数量
    const val MAX_CANDIDATES_PER_QUADRANT = 6

    // RANSAC 重投影误差相对容差因子
    const val RANSAC_ERROR_FACTOR = 0.004f

    // 三级置信度划分阈值
    const val CONFIDENCE_HIGH_THRESHOLD = 0.78f
    const val CONFIDENCE_LOW_THRESHOLD = 0.48f

    // 组合评分权重 (加权几何评分)
    const val WEIGHT_SHAPE = 0.25f       // 平行与透视收敛得分
    const val WEIGHT_RATIO = 0.30f       // 长宽比与对角线一致性得分
    const val WEIGHT_TEMPLATE = 0.25f    // 模板先验结构吻合度
    const val WEIGHT_CONTRAST = 0.20f    // 候选块自身质量均值
}
