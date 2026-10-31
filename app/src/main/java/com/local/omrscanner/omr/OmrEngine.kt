package com.local.omrscanner.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.local.omrscanner.model.ExamConfig
import com.local.omrscanner.model.NormalizedRect
import com.local.omrscanner.model.Point2D
import com.local.omrscanner.model.QuestionGradeResult
import com.local.omrscanner.model.ScanRecord
import com.local.omrscanner.vision.CardDetectionEngine
import com.local.omrscanner.vision.CardGeometryEstimator
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 纯本地离线 OMR 图像识别与自动判卷引擎：
 * 1. 积分图加速的 Bradley-Roth 局部自适应二值化
 * 2. 高精度“实心黑块-留白白边”环形核反差自动寻角算法（强力免疫平板边框、反光阴影与UI干扰）
 * 3. 8自由度单应性矩阵（Homography）高斯消元求解与双线性插值透视矫正
 * 4. 局部自适应质心搜索（Adaptive Centering）：克服真实手机拍照微小透视偏差与镜头畸变
 * 5. 准考证号与自适应客观题双阈值判分与原图可视化批改标记图层
 */
object OmrEngine {

    private const val FILL_THRESHOLD = 0.24f
    private const val SMUDGE_WARNING_THRESHOLD = 0.15f

    // v1.0.1 黑白打印兼容阈值
    private const val BW_BASELINE_FULL_SELECT_THRESHOLD = 0.48f
    private const val BW_NET_FILL_THRESHOLD = 0.24f
    private const val BW_NET_RAW_MIN_THRESHOLD = 0.30f
    private const val BW_STUDENT_ID_NET_THRESHOLD = 0.22f

    data class ProcessedSheetOutput(
        val record: ScanRecord,
        val annotatedBitmap: Bitmap,
        val detectedCorners: List<Point2D>
    )

    /**
     * 自动检测图像中的四角定位点（v1.0.1 升级：调用 CardDetectionEngine 智能四角检测与几何估计）
     */
    fun autoDetectCorners(sourceBitmap: Bitmap): List<Point2D> {
        return CardDetectionEngine.autoDetectCorners(sourceBitmap)
    }

    /**
     * 执行全套 OMR 阅卷流程（支持传入用户确认/微调后的四角定位点）
     */
    fun processAnswerSheet(
        sourceBitmap: Bitmap,
        examConfig: ExamConfig,
        customCorners: List<Point2D>? = null,
        subjectiveScore: Float = 0f
    ): ProcessedSheetOutput {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        // 若用户已在确认界面微调了角点则优先使用，否则启用智能四角定位引擎
        val (corners, warpedBitmap) = if (customCorners != null && customCorners.size == 4) {
            val warped = CardGeometryEstimator.warpToCanonical(sourceBitmap, customCorners)
            Pair(customCorners, warped)
        } else {
            val detResult = CardDetectionEngine.detectAndWarp(sourceBitmap)
            if (detResult.warpedBitmap != null) {
                Pair(detResult.corners, detResult.warpedBitmap)
            } else {
                val fallbackCorners = findFourCornerFiducials(adaptiveThresholdBradley(sourceBitmap), srcW, srcH)
                val warped = CardGeometryEstimator.warpToCanonical(sourceBitmap, fallbackCorners)
                Pair(fallbackCorners, warped)
            }
        }

        val warpedBinary = adaptiveThresholdBradley(warpedBitmap, applyPencilErosion = true)

        // v1.0.1 识别考号：同列印刷底噪中位数自校准（黑白打印防线 3）
        val studentIdBuilder = StringBuilder()
        for (col in 0 until examConfig.studentIdDigits) {
            val colRatios = mutableListOf<Float>()
            for (digit in 0..9) {
                val rect = TemplateSpec.getStudentIdBubbleRect(col, digit, examConfig.studentIdDigits)
                val ratio = computeFillRatioAdaptive(
                    warpedBinary,
                    TemplateSpec.CANONICAL_WIDTH,
                    TemplateSpec.CANONICAL_HEIGHT,
                    rect,
                    searchRadiusX = 8,
                    searchRadiusY = 5
                )
                colRatios.add(ratio)
            }

            // 计算一列 10 个数字填涂率的中位数作为黑白印刷字模底噪基线
            val sortedRatios = colRatios.sorted()
            val medianNoise = (sortedRatios[4] + sortedRatios[5]) / 2f

            var bestDigit = 0
            var maxNet = -1f
            var maxRaw = -1f
            for (digit in 0..9) {
                val raw = colRatios[digit]
                val net = (raw - medianNoise).coerceAtLeast(0f)
                if (net > maxNet) {
                    maxNet = net
                    maxRaw = raw
                    bestDigit = digit
                }
            }

            // 净增黑度 >= 0.20 且原始黑度 >= 0.26 则判定有效填涂
            if (maxNet >= 0.20f && maxRaw >= 0.26f) {
                studentIdBuilder.append(bestDigit)
            } else if (maxRaw >= 0.38f) {
                studentIdBuilder.append(bestDigit)
            } else {
                studentIdBuilder.append("0")
            }
        }
        val recognizedStudentId = studentIdBuilder.toString()

        // v1.0.1 识别客观题并判分：同题最小印刷底噪扣除法（Baseline Subtraction）
        val gradeResults = mutableListOf<QuestionGradeResult>()
        var totalObjectiveScore = 0f
        var maxTotalScore = 0f

        val activeQuestions = examConfig.questions.take(examConfig.activeQuestionCount)
        for (qConfig in activeQuestions) {
            maxTotalScore += qConfig.fullScore
            val rawRatios = mutableMapOf<String, Float>()
            var smudgeWarn = false

            TemplateSpec.OPTIONS.forEachIndexed { idx, optName ->
                val rect = TemplateSpec.getQuestionOptionBubbleRect(
                    questionNumber = qConfig.questionNumber,
                    optionIndex = idx,
                    questionCount = examConfig.activeQuestionCount
                )
                val fill = computeFillRatioAdaptive(
                    warpedBinary,
                    TemplateSpec.CANONICAL_WIDTH,
                    TemplateSpec.CANONICAL_HEIGHT,
                    rect,
                    searchRadiusX = 8,
                    searchRadiusY = 6
                )
                rawRatios[optName] = fill
            }

            // 计算同题 4 个选项中最小填涂率作为黑白字模底噪基线
            val minRaw = rawRatios.values.minOrNull() ?: 0f
            val baselineNoise = if (minRaw >= BW_BASELINE_FULL_SELECT_THRESHOLD) 0f else minRaw

            // 扣除底噪后的净增填涂率 (Net Fill Ratio)
            val netRatios = rawRatios.mapValues { (_, raw) ->
                (raw - baselineNoise).coerceAtLeast(0f)
            }

            val selected = mutableSetOf<String>()
            for ((optName, raw) in rawRatios) {
                val net = netRatios[optName] ?: 0f
                if ((net >= BW_NET_FILL_THRESHOLD && raw >= BW_NET_RAW_MIN_THRESHOLD) || raw >= 0.40f) {
                    selected.add(optName)
                } else if (net >= 0.12f && raw >= SMUDGE_WARNING_THRESHOLD) {
                    smudgeWarn = true
                }
            }

            // 轻涂补偿（净增显著度优先）
            if (selected.isEmpty()) {
                val bestEntry = netRatios.maxByOrNull { it.value }
                if (bestEntry != null && bestEntry.value >= 0.18f && (rawRatios[bestEntry.key] ?: 0f) >= 0.24f) {
                    selected.add(bestEntry.key)
                    smudgeWarn = true
                }
            }

            // 计算单选/多选得分
            val correctSet = qConfig.correctOptions
            val earned = when {
                selected.isEmpty() -> 0f
                selected == correctSet -> qConfig.fullScore
                correctSet.size > 1 && correctSet.containsAll(selected) -> qConfig.partialScore
                else -> 0f
            }
            totalObjectiveScore += earned

            gradeResults.add(
                QuestionGradeResult(
                    questionNumber = qConfig.questionNumber,
                    selectedOptions = selected,
                    correctOptions = correctSet,
                    earnedScore = earned,
                    maxScore = qConfig.fullScore,
                    optionFillRatios = rawRatios,
                    hasSmudgeWarning = smudgeWarn
                )
            )
        }

        val scanRecord = ScanRecord(
            id = UUID.randomUUID().toString(),
            examId = examConfig.id,
            studentId = recognizedStudentId,
            objectiveScore = totalObjectiveScore,
            subjectiveScore = subjectiveScore,
            maxTotalScore = maxTotalScore,
            results = gradeResults,
            timestampMillis = System.currentTimeMillis()
        )

        val annotated = renderAnnotatedSheet(warpedBitmap, scanRecord, examConfig.activeQuestionCount)

        return ProcessedSheetOutput(
            record = scanRecord,
            annotatedBitmap = annotated,
            detectedCorners = corners
        )
    }

    /**
     * 从标准样卷中提取答案
     */
    fun extractAnswerKeyFromMasterSheet(
        sourceBitmap: Bitmap,
        activeQuestionCount: Int
    ): Map<Int, Set<String>> {
        val corners = autoDetectCorners(sourceBitmap)
        val warpedBitmap = CardGeometryEstimator.warpToCanonical(sourceBitmap, corners)
        val warpedBinary = adaptiveThresholdBradley(warpedBitmap, applyPencilErosion = true)

        val extracted = mutableMapOf<Int, Set<String>>()
        for (qNum in 1..activeQuestionCount) {
            val selected = mutableSetOf<String>()
            val rawRatios = mutableMapOf<String, Float>()
            TemplateSpec.OPTIONS.forEachIndexed { idx, optName ->
                val rect = TemplateSpec.getQuestionOptionBubbleRect(
                    questionNumber = qNum,
                    optionIndex = idx,
                    questionCount = activeQuestionCount
                )
                val fill = computeFillRatioAdaptive(
                    warpedBinary,
                    TemplateSpec.CANONICAL_WIDTH,
                    TemplateSpec.CANONICAL_HEIGHT,
                    rect
                )
                rawRatios[optName] = fill
            }

            val minRaw = rawRatios.values.minOrNull() ?: 0f
            val baselineNoise = if (minRaw >= BW_BASELINE_FULL_SELECT_THRESHOLD) 0f else minRaw
            val netRatios = rawRatios.mapValues { (_, raw) -> (raw - baselineNoise).coerceAtLeast(0f) }

            for ((optName, raw) in rawRatios) {
                val net = netRatios[optName] ?: 0f
                if ((net >= BW_NET_FILL_THRESHOLD && raw >= BW_NET_RAW_MIN_THRESHOLD) || raw >= 0.40f) {
                    selected.add(optName)
                }
            }
            if (selected.isEmpty()) {
                val best = netRatios.maxByOrNull { it.value }
                if (best != null && best.value >= 0.18f && (rawRatios[best.key] ?: 0f) >= 0.22f) {
                    selected.add(best.key)
                }
            }
            if (selected.isNotEmpty()) {
                extracted[qNum] = selected
            }
        }
        return extracted
    }

    /**
     * Bradley-Roth 自适应二值化（v1.0.1 升级版：自适应红盲 + 黑白打印兼容）
     *
     * @param applyPencilErosion 是否启用 4 邻域实心度腐蚀过滤（v1.0.1 黑白打印防线 1）
     *        启用后仅保留周围 4 邻域全黑的实心铅笔涂点核心像素，
     *        可剥离黑白打印产生的 1~2px 印刷细线、方括号与空心字模。
     */
    private fun adaptiveThresholdBradley(
        bitmap: Bitmap,
        applyPencilErosion: Boolean = false
    ): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val integral = LongArray((w + 1) * (h + 1))
        val gray = IntArray(w * h)

        // v1.0.1: 统计红色像素占比，用于判定是彩色打印卷还是黑白打印卷
        var redPixelCount = 0
        var totalPixelCount = 0

        for (y in 0 until h) {
            var rowSum = 0L
            val rowOffset = y * w
            val intRowOffset = (y + 1) * (w + 1)
            val prevIntRowOffset = y * (w + 1)
            for (x in 0 until w) {
                val c = pixels[rowOffset + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // 中高考考务级红盲光学过滤（Red-Dropout Filter）：
                // 标准答题卡的红框、红色选项括号 [A][B][C][D] 及准考证号红字：
                // v1.0.1 升级：同时兼容高明度浅珊瑚红 #FF8A8A 与传统考务红 #E50012
                val gbAvg = (g + b) / 2f
                val isRedInk = (r > 80) && ((r / max(1f, gbAvg)) > 1.28f) && ((r - g) > 18) && ((r - b) > 18)

                if (isRedInk) redPixelCount++
                totalPixelCount++

                val lum = if (isRedInk) {
                    255 // 红色印刷线条在红盲下完全消除为白底，彻底消除红框误识别！
                } else {
                    // 非红色像素（包括2B铅笔石墨与定位黑块）：采用红通道强加权
                    min(255, (r * 180 + g * 50 + b * 26) shr 8)
                }

                gray[rowOffset + x] = lum
                rowSum += lum
                integral[intRowOffset + x + 1] = integral[prevIntRowOffset + x + 1] + rowSum
            }
        }

        val rawBinary = BooleanArray(w * h)
        val radius = max(12, min(w, h) / 28)
        val tFactor = 82

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
                rawBinary[rowOffset + x] = (lum * 100L) < (sum * tFactor / count)
            }
        }

        // v1.0.1 黑白打印防线 1：4 邻域实心度腐蚀过滤
        // 当检测到几乎无红色像素（黑白打印件）或显式启用时，
        // 仅保留上下左右 4 邻域全为黑的核心像素（实心铅笔涂点），
        // 剥离黑白打印出来的 1~2px 细线、方括号与空心字母笔画
        val isBlackWhitePrint = redPixelCount.toFloat() / max(1, totalPixelCount) < 0.005f
        if (applyPencilErosion || isBlackWhitePrint) {
            val eroded = BooleanArray(w * h)
            for (y in 1 until h - 1) {
                val rowOffset = y * w
                for (x in 1 until w - 1) {
                    if (rawBinary[rowOffset + x] &&
                        rawBinary[rowOffset + x - 1] &&      // 左
                        rawBinary[rowOffset + x + 1] &&      // 右
                        rawBinary[(y - 1) * w + x] &&        // 上
                        rawBinary[(y + 1) * w + x]           // 下
                    ) {
                        eroded[rowOffset + x] = true
                    }
                }
            }
            return eroded
        }

        return rawBinary
    }

    /**
     * 高精度“黑块-留白边”环形核反差自动寻角算法：
     * 强力免疫平板反光、外框、UI按钮及背景桌面干扰
     */
    fun findFourCornerFiducials(
        binary: BooleanArray,
        w: Int,
        h: Int
    ): List<Point2D> {
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                if (binary[y * w + x]) rowSum++
                integral[(y + 1) * (w + 1) + (x + 1)] =
                    integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }

        fun query(x1: Int, y1: Int, x2: Int, y2: Int): Int {
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

        val baseSize = max(14, (min(w, h) * 0.026f).toInt())
        val pad = max(6, baseSize / 3)

        fun findBestFiducialInQuadrant(rx1: Int, rx2: Int, ry1: Int, ry2: Int, targetX: Float, targetY: Float): Point2D {
            var bestScore = -1f
            var bestPos: Point2D? = null
            val step = max(2, baseSize / 6)

            for (sz in listOf(baseSize - 2, baseSize, baseSize + 3)) {
                if (sz <= 8) continue
                val innerArea = (sz * sz).toFloat()
                val outerArea = ((sz + 2 * pad) * (sz + 2 * pad) - sz * sz).toFloat()

                var y = ry1
                while (y <= ry2 - sz) {
                    var x = rx1
                    while (x <= rx2 - sz) {
                        val innerDark = query(x, y, x + sz, y + sz)
                        val innerDensity = innerDark / innerArea
                        if (innerDensity >= 0.55f) {
                            val totalWithCollar = query(x - pad, y - pad, x + sz + pad, y + sz + pad)
                            val outerDark = totalWithCollar - innerDark
                            val outerDensity = outerDark / outerArea
                            // 优质黑块判定：内部极黑，外部必须为留白（反差得分）
                            val collarScore = innerDensity - 1.6f * outerDensity
                            if (collarScore > bestScore) {
                                bestScore = collarScore
                                bestPos = Point2D(x + sz / 2f, y + sz / 2f)
                            }
                        }
                        x += step
                    }
                    y += step
                }
            }

            return bestPos ?: Point2D(targetX, targetY)
        }

        val midX = w / 2
        val midY = h / 2
        val mX = w * 0.035f
        val mY = h * 0.035f

        val tl = findBestFiducialInQuadrant(0, midX, 0, midY, mX, mY)
        val tr = findBestFiducialInQuadrant(midX, w, 0, midY, w - mX, mY)
        val br = findBestFiducialInQuadrant(midX, w, midY, h, w - mX, h - mY)
        val bl = findBestFiducialInQuadrant(0, midX, midY, h, mX, h - mY)

        return listOf(tl, tr, br, bl)
    }

    /**
     * 单应性透视变换
     */
    private fun warpPerspectiveToCanonical(
        src: Bitmap,
        tl: Point2D,
        tr: Point2D,
        br: Point2D,
        bl: Point2D,
        dstW: Int,
        dstH: Int
    ): Bitmap {
        val dstPts = arrayOf(
            Point2D(dstW * 0.02f, dstH * 0.015f),
            Point2D(dstW * 0.98f, dstH * 0.015f),
            Point2D(dstW * 0.98f, dstH * 0.985f),
            Point2D(dstW * 0.02f, dstH * 0.985f)
        )
        val srcPts = arrayOf(tl, tr, br, bl)

        val hMat = solveHomography(dstPts, srcPts)

        val srcW = src.width
        val srcH = src.height
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val yf = y.toDouble()
            val rowOffset = y * dstW
            for (x in 0 until dstW) {
                val xf = x.toDouble()
                val denom = hMat[6] * xf + hMat[7] * yf + 1.0
                if (abs(denom) > 1e-7) {
                    val srcX = ((hMat[0] * xf + hMat[1] * yf + hMat[2]) / denom).toInt()
                    val srcY = ((hMat[3] * xf + hMat[4] * yf + hMat[5]) / denom).toInt()

                    if (srcX in 0 until srcW && srcY in 0 until srcH) {
                        dstPixels[rowOffset + x] = srcPixels[srcY * srcW + srcX]
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

    private fun solveHomography(from: Array<Point2D>, to: Array<Point2D>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = from[i].x.toDouble()
            val y = from[i].y.toDouble()
            val u = to[i].x.toDouble()
            val v = to[i].y.toDouble()

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
            if (abs(pivot) < 1e-9) continue

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
     * 局部自适应质心搜索黑度计算（v1.0.1 防吸边升级版）：
     * 克服拍摄倾斜、微小透视畸变及镜头边缘形变，在候选窗口内自适应寻找 2B 铅笔黑度峰值
     *
     * v1.0.1 升级特性（黑白打印防线 2）：
     * 1. 收紧垂直内缩比例至 0.20f，让基础采样框远离单元格上下方的黑色表格横线
     * 2. 引入滑窗中心距离高斯衰减权重 R_weighted = R_raw * (1.0 - 0.018 * (|dx| + |dy|))
     *    防止未涂选项向边缘滑窗时“吸边”到黑色表格线
     */
    private fun computeFillRatioAdaptive(
        binary: BooleanArray,
        w: Int,
        h: Int,
        normRect: NormalizedRect,
        searchRadiusX: Int = 8,
        searchRadiusY: Int = 6
    ): Float {
        val baseX1 = (normRect.left * w).toInt().coerceIn(0, w - 1)
        val baseX2 = (normRect.right * w).toInt().coerceIn(baseX1 + 1, w)
        val baseY1 = (normRect.top * h).toInt().coerceIn(0, h - 1)
        val baseY2 = (normRect.bottom * h).toInt().coerceIn(baseY1 + 1, h)
        val rectW = baseX2 - baseX1
        val rectH = baseY2 - baseY1
        if (rectW <= 4 || rectH <= 4) return 0f

        // v1.0.1: 核心有效采样子区域向内收缩 18%~20%，避开外部轮廓边界与表格线
        val insetX = max(1, (rectW * 0.14f).toInt())
        val insetY = max(1, (rectH * 0.20f).toInt())
        val innerW = rectW - insetX * 2
        val innerH = rectH - insetY * 2
        val totalCount = innerW * innerH
        if (totalCount <= 0) return 0f

        var maxWeightedDark = 0f
        val step = 2

        for (dy in -searchRadiusY..searchRadiusY step step) {
            val y1 = (baseY1 + dy + insetY).coerceIn(0, h - innerH)
            val y2 = y1 + innerH
            for (dx in -searchRadiusX..searchRadiusX step step) {
                val x1 = (baseX1 + dx + insetX).coerceIn(0, w - innerW)
                val x2 = x1 + innerW
                var dark = 0
                for (y in y1 until y2) {
                    val rowOffset = y * w
                    for (x in x1 until x2) {
                        if (binary[rowOffset + x]) dark++
                    }
                }

                // v1.0.1 防线 2：中心距离衰减权重，只有真正的实心涂块才能克服距离衰减赢得峰值
                val distDecay = (1.0f - 0.018f * (abs(dx) + abs(dy))).coerceAtLeast(0.60f)
                val weighted = dark.toFloat() * distDecay
                if (weighted > maxWeightedDark) {
                    maxWeightedDark = weighted
                }
            }
        }

        return maxWeightedDark / totalCount
    }

    /**
     * 在矫正后的答题卡图像上绘制红/绿/黄批改框与右上角总分印章
     */
    private fun renderAnnotatedSheet(warped: Bitmap, record: ScanRecord, totalQuestions: Int): Bitmap {
        val resultBmp = warped.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBmp)
        val w = resultBmp.width.toFloat()
        val h = resultBmp.height.toFloat()

        val paintCorrect = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(22, 163, 74) // 绿色：正确
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val paintWrong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 38, 38) // 红色：错选
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val paintMissedHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 99, 235) // 蓝色：未选的标准答案位置
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val paintWarning = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(217, 119, 6) // 琥珀黄：疑似擦除不净预警
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        for (qResult in record.results) {
            TemplateSpec.OPTIONS.forEachIndexed { idx, optName ->
                val nRect = TemplateSpec.getQuestionOptionBubbleRect(
                    questionNumber = qResult.questionNumber,
                    optionIndex = idx,
                    questionCount = totalQuestions
                )
                val rectF = RectF(
                    nRect.left * w,
                    nRect.top * h,
                    nRect.right * w,
                    nRect.bottom * h
                )
                val isSelected = qResult.selectedOptions.contains(optName)
                val isCorrectOpt = qResult.correctOptions.contains(optName)

                when {
                    isSelected && isCorrectOpt -> canvas.drawRect(rectF, paintCorrect)
                    isSelected && !isCorrectOpt -> canvas.drawRect(rectF, paintWrong)
                    !isSelected && isCorrectOpt -> canvas.drawRect(rectF, paintMissedHint)
                    !isSelected && !isCorrectOpt && (qResult.optionFillRatios[optName] ?: 0f) >= SMUDGE_WARNING_THRESHOLD ->
                        canvas.drawRect(rectF, paintWarning)
                }
            }
        }

        // 顶部成绩印章
        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 38, 38)
            textSize = 44f
            isFakeBoldText = true
        }
        canvas.drawText(
            "考号: ${record.studentId}   总分: ${record.totalScore} / ${record.maxTotalScore}",
            w * 0.05f,
            h * 0.075f,
            stampPaint
        )

        return resultBmp
    }
}
