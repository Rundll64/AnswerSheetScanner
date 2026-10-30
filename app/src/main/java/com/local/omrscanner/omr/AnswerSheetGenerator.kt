package com.local.omrscanner.omr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 国家标准化考试规范答题卡生成与导出引擎：
 * 严格参照全国中高考、大学四六级及统考答题卡标准排版规范设计：
 * 1. 经典中高考红标双色风格（Pantone 185C 红标外框 + 纯黑定位锚点 + 2B铅笔石墨填涂）
 * 2. 规范考务要素：条形码粘贴区、四角视觉定位黑块、左侧光电同步时钟标、考生须知与填涂规范、缺考违纪标记
 * 3. 准考证号 / 学号填涂区（4~10位手写格与纵向0~9胶囊填涂列）
 * 4. 第 Ⅰ 卷 客观选择题作答区：
 *    - 选项明确印制为标准 [A] [B] [C] [D] 方括号格式，方便学生填涂识别
 *    - 自适应题量网格：题少时自动缩减占用空间，绝不强行拉伸占满全页
 *    - 5题一组防串行分组隔离
 * 5. 第 Ⅱ 卷 非选择题（解答题）作答区：
 *    - 题数完全可自定义（0~5道题）
 *    - 每题书写行数完全可自定义（2~20行），题少时自动向下扩展书写区
 *    - 教师评卷统分表（动态题号列）
 * 6. 支持导出可编辑 Microsoft Word (.docx) 文档与标准国际 A4 尺寸打印版 PDF
 */
object AnswerSheetGenerator {

    enum class SheetColorMode(val label: String) {
        CORAL_RED("高明度浅珊瑚红（双模推荐，兼容黑白/彩色）"),
        LIGHT_GRAY("黑白浅灰（纯黑白激光/复印机专用）"),
        CLASSIC_RED("经典考务红（传统彩色印刷）")
    }

    data class SheetConfig(
        val title: String = "标准化考试答题卡",
        val subject: String = "通用科目",
        val questionCount: Int = 30,
        val studentIdDigits: Int = 8,
        val optionsCount: Int = 4,        // 3: A-C, 4: A-D, 5: A-E
        val isPartitioned: Boolean = true, // 每5题分区带隔离线
        val fillDemoMode: Boolean = false, // false: 空白供打印, true: 模拟填涂样卷
        val simulatedStudentId: String = "20260815",
        // 自定义解答题（非选择题）参数
        val subjectiveCount: Int = 2,      // 解答题数量: 0 ~ 5 题
        val subjectiveLinesPerQuestion: Int = 8, // 每题书写行数: 2 ~ 20 行
        // v1.0.1: 双模打印色调配置
        val colorMode: SheetColorMode = SheetColorMode.CORAL_RED
    )

    const val WIDTH = 1200
    const val HEIGHT = 1700

    /**
     * 动态绘制高保真全国统考标准 A4 答题卡 Bitmap（v1.0.1 升级版：兼容双模打印与四角白边净空区）
     */
    fun generateSheetBitmap(config: SheetConfig): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        // v1.0.1: 根据双模配置选取印刷颜色
        val examRed = when (config.colorMode) {
            SheetColorMode.CORAL_RED -> Color.rgb(255, 138, 138) // 高明度浅珊瑚红 #FF8A8A (等效黑白亮度 173)
            SheetColorMode.LIGHT_GRAY -> Color.rgb(181, 181, 181) // 黑白浅灰 #B5B5B5
            SheetColorMode.CLASSIC_RED -> Color.rgb(220, 38, 38)  // 经典考务深红
        }
        val examRedBg = when (config.colorMode) {
            SheetColorMode.CORAL_RED -> Color.rgb(255, 246, 246)
            SheetColorMode.LIGHT_GRAY -> Color.rgb(248, 250, 252)
            SheetColorMode.CLASSIC_RED -> Color.rgb(254, 242, 242)
        }
        val slateGray = Color.rgb(71, 85, 105)
        val darkText = Color.rgb(15, 23, 42)
        val pencilBlack = Color.rgb(38, 38, 38)
        val lineGray = Color.rgb(226, 232, 240)

        val blackSolid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val redStrokeThick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
        }
        val redStrokeThin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            style = Paint.Style.STROKE
            strokeWidth = 1.0f // v1.0.1: 细线控制在 1.0px，黑白打印下被形态学腐蚀轻松消除
        }
        val redDashBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            style = Paint.Style.FILL
        }
        val redBgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRedBg
            style = Paint.Style.FILL
        }
        val pencilFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pencilBlack
            style = Paint.Style.FILL
        }
        val grayLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineGray
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
        }

        // ==================== 1. 四角定位标记 (Fiducial Markers, 严格对接 TemplateSpec) ====================
        val markerSize = TemplateSpec.MARKER_SIZE
        for (pt in TemplateSpec.CANONICAL_MARKERS) {
            canvas.drawRect(
                pt.x - markerSize / 2f,
                pt.y - markerSize / 2f,
                pt.x + markerSize / 2f,
                pt.y + markerSize / 2f,
                blackSolid
            )
        }

        // ==================== 2. 左侧时序同步头 (Timing Track Marks) ====================
        val timingLeft = 18f
        val timingRight = 32f
        val (dynCols, dynRows) = TemplateSpec.getLayoutMetrics(config.questionCount)
        for (r in 0 until dynRows) {
            val yCenter = if (r < 5) 508f + r * 46f + 17f else 508f + 16f + r * 46f + 17f
            canvas.drawRect(timingLeft, yCenter - 6f, timingRight, yCenter + 6f, blackSolid)
        }
        canvas.drawRect(timingLeft, 220f, timingRight, 232f, blackSolid)
        canvas.drawRect(timingLeft, 380f, timingRight, 392f, blackSolid)
        canvas.drawRect(timingLeft, 1100f, timingRight, 1112f, blackSolid)
        canvas.drawRect(timingLeft, 1380f, timingRight, 1392f, blackSolid)

        // ==================== 3. 答题卡主标题与考务警示 ====================
        val titleText = if (config.title.contains("考试") || config.title.contains("测试")) {
            config.title
        } else {
            "${config.title} · 标准化考试答题卡"
        }
        val mainTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 30f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(titleText, WIDTH / 2f, 54f, mainTitlePaint)

        val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkText
            textSize = 17f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val partitionHint = if (config.isPartitioned) "【5题分组防串行规范版】" else "【标准版】"
        canvas.drawText(
            "科目: ${config.subject}    |    选择题: 共 ${config.questionCount} 题    |    准考证号: ${config.studentIdDigits} 位 $partitionHint",
            WIDTH / 2f,
            86f,
            subTitlePaint
        )

        val cautionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 13.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "考生注意：请在各题目的答题区域内作答，超出红色矩形边框限定区域的答案无效",
            WIDTH / 2f,
            110f,
            cautionPaint
        )

        // ==================== 4. 考生信息与考务三大标准框 (Y: 125 ~ 415) ====================
        // [框1 - 左侧] 考生手写信息与注意事项 (X: 45 ~ 460)
        val box1Rect = RectF(45f, 125f, 460f, 415f)
        canvas.drawRoundRect(box1Rect, 4f, 4f, redStrokeThin)

        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkText
            textSize = 15.5f
            isFakeBoldText = true
        }
        canvas.drawText("姓    名：__________________", box1Rect.left + 14f, box1Rect.top + 28f, infoPaint)
        canvas.drawText("所属班级：__________________", box1Rect.left + 14f, box1Rect.top + 56f, infoPaint)
        canvas.drawText("考场/座号：________ / ________", box1Rect.left + 14f, box1Rect.top + 84f, infoPaint)
        canvas.drawLine(box1Rect.left + 8f, box1Rect.top + 98f, box1Rect.right - 8f, box1Rect.top + 98f, redStrokeThin)

        val noticeTagRect = RectF(box1Rect.left + 14f, box1Rect.top + 106f, box1Rect.left + 108f, box1Rect.top + 126f)
        canvas.drawRoundRect(noticeTagRect, 3f, 3f, redFill)
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("注意事项", noticeTagRect.centerX(), noticeTagRect.centerY() + 4.5f, tagTextPaint)

        val noticeItemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateGray
            textSize = 12.5f
        }
        canvas.drawText("1. 答题前先将姓名、准考证号填写清楚，核准条形码。", box1Rect.left + 14f, box1Rect.top + 146f, noticeItemPaint)
        canvas.drawText("2. 客观选择题必须使用 2B 铅笔规范填涂，修改用橡皮擦净。", box1Rect.left + 14f, box1Rect.top + 168f, noticeItemPaint)
        canvas.drawText("3. 严格按题号在指定答题区作答，超出答题区书写无效。", box1Rect.left + 14f, box1Rect.top + 190f, noticeItemPaint)
        canvas.drawText("4. 保持卡面清洁，严禁折叠、破损、污染，严禁做任何标记。", box1Rect.left + 14f, box1Rect.top + 212f, noticeItemPaint)

        val sampleTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkText
            textSize = 13f
            isFakeBoldText = true
        }
        canvas.drawText("正确填涂: [ ■ ]", box1Rect.left + 14f, box1Rect.top + 242f, sampleTitlePaint)
        canvas.drawText("错误填涂: [ √ ]  [ × ]  [/]  [ ● ]", box1Rect.left + 140f, box1Rect.top + 242f, noticeItemPaint)

        val pencilHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 12f
            isFakeBoldText = true
        }
        canvas.drawText("（必须使用正规 2B 铅笔浓黑填涂，机器方可精准判分）", box1Rect.left + 14f, box1Rect.top + 270f, pencilHintPaint)

        // [框2 - 中间] 条形码粘贴区与考务标记 (X: 475 ~ 720)
        val box2TopRect = RectF(475f, 125f, 720f, 280f)
        canvas.drawRoundRect(box2TopRect, 4f, 4f, redDashBorder)
        val barcodeTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 14f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("条形码粘贴区", box2TopRect.centerX(), box2TopRect.top + 26f, barcodeTitlePaint)
        val barcodeSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateGray
            textSize = 11.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("（请居中贴在虚线框内，切勿贴出框外）", box2TopRect.centerX(), box2TopRect.top + 46f, barcodeSubPaint)

        val barY1 = box2TopRect.top + 60f
        val barY2 = box2TopRect.bottom - 24f
        val barStartX = box2TopRect.left + 22f
        val barWidth = box2TopRect.width() - 44f
        val rand = Random(2026)
        var curBx = barStartX
        while (curBx < barStartX + barWidth) {
            val sw = if (rand.nextBoolean()) 2f else 4f
            canvas.drawRect(curBx, barY1, curBx + sw, barY2, Paint().apply { color = Color.rgb(71, 85, 105) })
            curBx += sw + if (rand.nextBoolean()) 3f else 5f
        }
        canvas.drawText("* 2026 8888 01 *", box2TopRect.centerX(), box2TopRect.bottom - 8f, barcodeSubPaint)

        val box2BottomRect = RectF(475f, 290f, 720f, 415f)
        canvas.drawRoundRect(box2BottomRect, 4f, 4f, redStrokeThin)
        val markerHeaderRect = RectF(box2BottomRect.left, box2BottomRect.top, box2BottomRect.right, box2BottomRect.top + 24f)
        canvas.drawRect(markerHeaderRect, redBgFill)
        canvas.drawLine(box2BottomRect.left, box2BottomRect.top + 24f, box2BottomRect.right, box2BottomRect.top + 24f, redStrokeThin)
        val markHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 12.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("考务标记（考生禁填）", box2BottomRect.centerX(), box2BottomRect.top + 17f, markHeaderPaint)

        val markItemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkText
            textSize = 13.5f
            isFakeBoldText = true
        }
        canvas.drawText("缺考标记: [   ]", box2BottomRect.left + 20f, box2BottomRect.top + 58f, markItemPaint)
        canvas.drawText("违纪标记: [   ]", box2BottomRect.left + 130f, box2BottomRect.top + 58f, markItemPaint)
        val teacherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateGray
            textSize = 11.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("（由监考教师核验后使用 2B 铅笔填涂）", box2BottomRect.centerX(), box2BottomRect.top + 94f, teacherPaint)

        // [框3 - 右侧] 准考证号 / 学号填涂区 (X: 735 ~ 1155)
        val box3Rect = RectF(735f, 125f, 1155f, 415f)
        canvas.drawRoundRect(box3Rect, 4f, 4f, redStrokeThick)

        val idHeaderRect = RectF(box3Rect.left, box3Rect.top, box3Rect.right, box3Rect.top + 28f)
        canvas.drawRect(idHeaderRect, redFill)
        val idTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("准  考  证  号  填  涂  区", box3Rect.centerX(), box3Rect.top + 20f, idTitlePaint)

        val digits = config.studentIdDigits.coerceIn(4, 10)
        val idColWidth = (box3Rect.width() - 16f) / digits.toFloat()
        val paddedSimulatedId = config.simulatedStudentId.padStart(digits, '0').takeLast(digits)

        val writeBoxTop = box3Rect.top + 34f
        val writeBoxHeight = 32f
        for (i in 0 until digits) {
            val cLeft = box3Rect.left + 8f + i * idColWidth
            val cRight = cLeft + idColWidth - 4f
            val wRect = RectF(cLeft, writeBoxTop, cRight, writeBoxTop + writeBoxHeight)
            canvas.drawRect(wRect, redStrokeThin)

            if (config.fillDemoMode) {
                val digitChar = paddedSimulatedId[i].toString()
                val charPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 20f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(digitChar, wRect.centerX(), wRect.centerY() + 7f, charPaint)
            }
        }

        val digitLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 14.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (col in 0 until digits) {
            val targetDigit = if (config.fillDemoMode) paddedSimulatedId[col].digitToIntOrNull() ?: 0 else -1

            for (d in 0..9) {
                val nRect = TemplateSpec.getStudentIdBubbleRect(col, d, digits)
                val bRect = RectF(
                    nRect.left * WIDTH,
                    nRect.top * HEIGHT,
                    nRect.right * WIDTH,
                    nRect.bottom * HEIGHT
                )

                canvas.drawRoundRect(bRect, 4f, 4f, redStrokeThin)
                canvas.drawText("[$d]", bRect.centerX(), bRect.centerY() + 5.2f, digitLabelPaint)

                if (config.fillDemoMode && d == targetDigit) {
                    canvas.drawRoundRect(bRect, 4f, 4f, pencilFill)
                }
            }
        }

        // ==================== 5. 第 Ⅰ 卷 客观选择题作答区 (自适应版式，不占多余空间) ====================
        val qAreaTop = 435f
        val (totalCols, rowsPerCol) = TemplateSpec.getLayoutMetrics(config.questionCount)

        // 动态计算选择题区域底高：如果只有10题（5行），高度大大降低；仅在题多时延伸至 995f
        val qContentHeight = rowsPerCol * 46f + (if (rowsPerCol > 5 && config.isPartitioned) 16f else 0f)
        val qAreaBottom = qAreaTop + 34f + 32f + qContentHeight + 14f
        val qBoxRect = RectF(45f, qAreaTop, 1155f, qAreaBottom)
        canvas.drawRoundRect(qBoxRect, 4f, 4f, redStrokeThick)

        // 客观题总标题横幅（红底白字）
        val qHeaderBanner = RectF(qBoxRect.left, qBoxRect.top, qBoxRect.right, qBoxRect.top + 34f)
        canvas.drawRect(qHeaderBanner, redFill)
        val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "第 Ⅰ 卷  选择题作答区（共 ${config.questionCount} 小题，请用 2B 铅笔规范填涂）",
            qBoxRect.centerX(),
            qBoxRect.top + 23f,
            bannerTextPaint
        )

        val colWidth = qBoxRect.width() / totalCols.toFloat()
        val optList = when (config.optionsCount) {
            3 -> listOf("A", "B", "C")
            5 -> listOf("A", "B", "C", "D", "E")
            else -> listOf("A", "B", "C", "D")
        }

        val qNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateGray // v1.0.1: 题号采用深灰，保证学生阅读清晰，黑白复印与右侧选项彻底隔离
            textSize = 15.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val optLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 16f
            isFakeBoldText = false // v1.0.1: 细体字模，黑白打印下呈现浅灰，腐蚀过滤后零残留
            textAlign = Paint.Align.CENTER
        }
        val colHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 13.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (colIdx in 0 until totalCols) {
            val cLeft = qBoxRect.left + colIdx * colWidth
            val cRight = cLeft + colWidth

            if (colIdx > 0) {
                canvas.drawLine(cLeft, qAreaTop + 34f, cLeft, qAreaBottom, redStrokeThin)
            }

            val startQ = colIdx * rowsPerCol + 1
            val endQ = minOf((colIdx + 1) * rowsPerCol, config.questionCount)
            val colHeadRect = RectF(cLeft, qAreaTop + 34f, cRight, qAreaTop + 66f)
            canvas.drawRect(colHeadRect, redBgFill)
            canvas.drawLine(cLeft, qAreaTop + 66f, cRight, qAreaTop + 66f, redStrokeThin)
            canvas.drawText("第 %02d ~ %02d 题".format(startQ, endQ), colHeadRect.centerX(), colHeadRect.centerY() + 5f, colHeaderPaint)

            if (config.isPartitioned && rowsPerCol > 5) {
                canvas.drawLine(cLeft + 6f, 744f, cRight - 6f, 744f, redDashBorder)
            }

            for (row in 0 until rowsPerCol) {
                val qNum = colIdx * rowsPerCol + row + 1

                val firstOptNRect = TemplateSpec.getQuestionOptionBubbleRect(qNum, 0, config.questionCount, optList.size)
                val rowCenterY = (firstOptNRect.top + firstOptNRect.bottom) * HEIGHT / 2f
                val qNumCenterX = cLeft + colWidth * 0.11f

                if (qNum <= config.questionCount) {
                    canvas.drawText("%02d".format(qNum), qNumCenterX, rowCenterY + 5.5f, qNumPaint)

                    val simChosen = if (config.fillDemoMode) {
                        optList[((qNum * 7 + 3) % optList.size)]
                    } else null

                    // 绘制各选项气泡，按照用户明确要求的【A】方括号格式渲染！
                    for ((optIdx, optLetter) in optList.withIndex()) {
                        val nRect = TemplateSpec.getQuestionOptionBubbleRect(qNum, optIdx, config.questionCount, optList.size)
                        val bRect = RectF(
                            nRect.left * WIDTH,
                            nRect.top * HEIGHT,
                            nRect.right * WIDTH,
                            nRect.bottom * HEIGHT
                        )

                        // 绘制胶囊外框与方括号选项文本 [A] / [B] / [C] / [D]
                        canvas.drawRoundRect(bRect, 4f, 4f, redStrokeThin)
                        canvas.drawText("[$optLetter]", bRect.centerX(), bRect.centerY() + 5.8f, optLetterPaint)

                        if (config.fillDemoMode && optLetter == simChosen) {
                            canvas.drawRoundRect(bRect, 4f, 4f, pencilFill)
                        }
                    }
                } else {
                    val dummyLineY = rowCenterY
                    canvas.drawLine(cLeft + colWidth * 0.15f, dummyLineY, cRight - colWidth * 0.15f, dummyLineY, grayLinePaint)
                }
            }
        }

        // ==================== 6. 第 Ⅱ 卷 非选择题（解答题）作答区 (动态承接上方省出空间) ====================
        val subAreaTop = qAreaBottom + 18f
        val subAreaBottom = 1640f
        val subBoxRect = RectF(45f, subAreaTop, 1155f, subAreaBottom)
        canvas.drawRoundRect(subBoxRect, 4f, 4f, redStrokeThick)

        val subHeaderBanner = RectF(subBoxRect.left, subBoxRect.top, subBoxRect.right, subBoxRect.top + 34f)
        canvas.drawRect(subHeaderBanner, redFill)
        canvas.drawText(
            "第 Ⅱ 卷  非选择题作答区（请用 0.5 毫米黑色墨水签字笔在各题规定区域内作答）",
            subBoxRect.centerX(),
            subBoxRect.top + 23f,
            bannerTextPaint
        )

        val subjCount = config.subjectiveCount.coerceIn(0, 5)
        val subjLines = config.subjectiveLinesPerQuestion.coerceIn(2, 20)

        // 教师评分记分表
        val scoreTableTop = subAreaTop + 40f
        val scoreTableBottom = scoreTableTop + 56f
        val scoreTableRect = RectF(subBoxRect.left + 8f, scoreTableTop, subBoxRect.right - 8f, scoreTableBottom)
        canvas.drawRect(scoreTableRect, redStrokeThin)

        val scoreCols = mutableListOf("题号", "一 (客观)")
        for (i in 1..maxOf(1, subjCount)) {
            scoreCols.add("第${config.questionCount + i}题")
        }
        scoreCols.add("总分")
        scoreCols.add("评卷人")
        scoreCols.add("复核人")

        val scoreColW = scoreTableRect.width() / scoreCols.size.toFloat()
        val scoreMidY = (scoreTableTop + scoreTableBottom) / 2f
        canvas.drawLine(scoreTableRect.left, scoreMidY, scoreTableRect.right, scoreMidY, redStrokeThin)

        val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = examRed
            textSize = 12f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val tableContentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateGray
            textSize = 11.5f
            textAlign = Paint.Align.CENTER
        }

        for ((idx, colName) in scoreCols.withIndex()) {
            val scLeft = scoreTableRect.left + idx * scoreColW
            if (idx > 0) {
                canvas.drawLine(scLeft, scoreTableTop, scLeft, scoreTableBottom, redStrokeThin)
            }
            canvas.drawText(colName, scLeft + scoreColW / 2f, scoreTableTop + 18f, tableHeaderPaint)
            val valText = if (idx == 0) "得分" else ""
            canvas.drawText(valText, scLeft + scoreColW / 2f, scoreTableBottom - 8f, tableContentPaint)
        }

        // 解答题作答线框（根据自定义题数均匀分配高度与行数）
        val answerAreaTop = scoreTableBottom + 10f
        val answerAreaBottom = subAreaBottom - 10f
        val totalWritingHeight = answerAreaBottom - answerAreaTop

        val subjectiveQTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkText
            textSize = 14f
            isFakeBoldText = true
        }

        if (subjCount > 0) {
            val singleBoxHeight = totalWritingHeight / subjCount.toFloat()

            for (si in 0 until subjCount) {
                val qn = config.questionCount + si + 1
                val score = 10 + si * 5
                val bTop = answerAreaTop + si * singleBoxHeight + 4f
                val bBottom = bTop + singleBoxHeight - 8f
                val singleBoxRect = RectF(subBoxRect.left + 8f, bTop, subBoxRect.right - 8f, bBottom)

                canvas.drawRoundRect(singleBoxRect, 4f, 4f, redStrokeThin)
                canvas.drawText(
                    "第 ${qn} 题. 综合解答题（${score}分）",
                    singleBoxRect.left + 16f,
                    singleBoxRect.top + 24f,
                    subjectiveQTitlePaint
                )

                // 绘制学生手写横线（行数依据用户自定义）
                val writingTop = singleBoxRect.top + 32f
                val writingBottom = singleBoxRect.bottom - 10f
                val lineSpacing = (writingBottom - writingTop) / (subjLines + 1).toFloat()

                for (li in 1..subjLines) {
                    val ly = writingTop + li * lineSpacing
                    if (ly < singleBoxRect.bottom - 8f) {
                        canvas.drawLine(singleBoxRect.left + 14f, ly, singleBoxRect.right - 14f, ly, grayLinePaint)
                    }
                }

                // 绘制考务水印提示
                val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(226, 232, 240)
                    textSize = 14f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(
                    "请在此区域内作答，超出红色矩形边框限定区域的书写内容无效",
                    singleBoxRect.centerX(),
                    singleBoxRect.centerY(),
                    watermarkPaint
                )
            }
        } else {
            // 纯客观卷，下方留作标准草稿与备用作答大框
            val singleBoxRect = RectF(subBoxRect.left + 8f, answerAreaTop + 4f, subBoxRect.right - 8f, answerAreaBottom - 4f)
            canvas.drawRoundRect(singleBoxRect, 4f, 4f, redStrokeThin)
            canvas.drawText("备用作答与演算草稿区（无解答题）", singleBoxRect.left + 16f, singleBoxRect.top + 28f, subjectiveQTitlePaint)
            for (li in 1..12) {
                val ly = singleBoxRect.top + 32f + li * 40f
                if (ly < singleBoxRect.bottom - 16f) {
                    canvas.drawLine(singleBoxRect.left + 14f, ly, singleBoxRect.right - 14f, ly, grayLinePaint)
                }
            }
        }

        // ==================== 7. 底部考务注脚 (Footer) ====================
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateGray
            textSize = 13.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "■▲ 底部定位基准区（请保持卡面平整，严禁在此区域及四角定位黑块边缘污损乱画） ▲■",
            WIDTH / 2f,
            HEIGHT - 32f,
            footerPaint
        )

        return bmp
    }

    /**
     * 导出为标准国际 A4 尺寸打印版 PDF 文件 (595 x 842 pt)
     */
    fun exportToPdf(context: Context, bitmap: Bitmap, title: String): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val destRect = Rect(0, 0, 595, 842)
        val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        page.canvas.drawBitmap(bitmap, null, destRect, filterPaint)
        pdfDocument.finishPage(page)

        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val outFile = File(context.filesDir, "${safeTitle}_A4打印标准答题卡_${sdf.format(Date())}.pdf")
        FileOutputStream(outFile).use { fos ->
            pdfDocument.writeTo(fos)
        }
        pdfDocument.close()
        return outFile
    }

    /**
     * 导出为标准可编辑 Microsoft Word (.docx) 文件
     */
    fun exportToDocx(context: Context, config: SheetConfig): File {
        return DocxAnswerSheetGenerator.exportToDocx(context, config)
    }

    /**
     * 自动备份至公共 Download 目录
     */
    fun saveToPublicDownloads(context: Context, sourceFile: File, displayName: String, mimeType: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { inp ->
                            inp.copyTo(out)
                        }
                    }
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!dlDir.exists()) dlDir.mkdirs()
                val target = File(dlDir, displayName)
                sourceFile.copyTo(target, overwrite = true)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 调起系统分享面板（支持微信/QQ/钉钉/WPS/打印）
     */
    fun shareExportedFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(chooserTitle, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
