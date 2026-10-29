package com.local.omrscanner.omr

import com.local.omrscanner.model.NormalizedRect

/**
 * 遵循全国中高考与国家标准化考试标准的归一化相对坐标规范。
 * 标准 A4 页面被透视变换映射为基准正视图：
 * 宽度 CANONICAL_WIDTH = 1200 px, 高度 CANONICAL_HEIGHT = 1700 px。
 */
object TemplateSpec {
    const val CANONICAL_WIDTH = 1200
    const val CANONICAL_HEIGHT = 1700

    val OPTIONS = listOf("A", "B", "C", "D")

    /**
     * 依据客观题总数自适应计算排版网格：
     * 少则低高度/少列（为下方解答题腾出充裕空间），多则标准5列50题排布。
     * 返回 Pair(总列数 totalCols, 每列题数 rowsPerCol)
     */
    fun getLayoutMetrics(questionCount: Int): Pair<Int, Int> {
        return when {
            questionCount <= 10 -> Pair(2, 5)  // 2列，每列5题，高度大幅缩减，为解答题腾出空间
            questionCount <= 15 -> Pair(3, 5)  // 3列，每列5题
            questionCount <= 20 -> Pair(4, 5)  // 4列，每列5题
            questionCount <= 30 -> Pair(3, 10) // 3列，每列10题
            questionCount <= 40 -> Pair(4, 10) // 4列，每列10题
            else -> Pair(5, 10)                // 5列，每列10题（满版50题）
        }
    }

    /**
     * 准考证号 / 学号填涂区：位于右上部（X: 735 ~ 1155 px, Y: 205 ~ 405 px）
     * 纵向 10 行（数字 0 ~ 9），横向支持 4 ~ 10 位动态等分
     */
    fun getStudentIdBubbleRect(
        colIndex: Int,
        digit: Int,
        totalDigits: Int = 8
    ): NormalizedRect {
        val gridLeft = 735f
        val gridRight = 1155f
        val gridTop = 205f
        val gridBottom = 405f

        val digits = totalDigits.coerceIn(4, 10)
        val colWidth = (gridRight - gridLeft) / digits.toFloat()
        val rowHeight = (gridBottom - gridTop) / 10f

        val cellLeft = gridLeft + colIndex.coerceIn(0, digits - 1) * colWidth
        val cellTop = gridTop + digit.coerceIn(0, 9) * rowHeight

        val padX = colWidth * 0.12f
        val padY = rowHeight * 0.10f

        return NormalizedRect(
            left = (cellLeft + padX) / CANONICAL_WIDTH,
            top = (cellTop + padY) / CANONICAL_HEIGHT,
            right = (cellLeft + colWidth - padX) / CANONICAL_WIDTH,
            bottom = (cellTop + rowHeight - padY) / CANONICAL_HEIGHT
        )
    }

    /**
     * 客观题作答区：
     * 依据题量自适应计算列数与行数：
     * 选项采用标准化 [A] [B] [C] [D] 括号矩形窗口。
     */
    fun getQuestionOptionBubbleRect(
        questionNumber: Int,
        optionIndex: Int,
        questionCount: Int = 50,
        optionsCount: Int = 4
    ): NormalizedRect {
        val (totalCols, rowsPerCol) = getLayoutMetrics(questionCount)
        val qIdx = (questionNumber - 1).coerceIn(0, questionCount - 1)
        val colGroup = (qIdx / rowsPerCol).coerceIn(0, totalCols - 1)
        val rowInCol = qIdx % rowsPerCol

        val qBoxLeft = 45f
        val qBoxRight = 1155f
        val colWidth = (qBoxRight - qBoxLeft) / totalCols.toFloat()

        val cLeft = qBoxLeft + colGroup * colWidth
        val qNumWidth = colWidth * 0.22f
        val optsStartLeft = cLeft + qNumWidth + 4f
        val optsTotalWidth = (colWidth - qNumWidth - 8f)
        val singleOptWidth = optsTotalWidth / optionsCount.toFloat()

        val startY = 508f
        val rowH = 46f
        val bubbleH = 34f
        val partitionGap = if (rowsPerCol > 5) 16f else 0f

        val rowTop = if (rowInCol < 5) {
            startY + rowInCol * rowH
        } else {
            startY + partitionGap + rowInCol * rowH
        }
        val rowBottom = rowTop + bubbleH

        val optLeft = optsStartLeft + optionIndex.coerceIn(0, optionsCount - 1) * singleOptWidth
        val padX = singleOptWidth * 0.08f
        val padY = 2f

        return NormalizedRect(
            left = (optLeft + padX) / CANONICAL_WIDTH,
            top = (rowTop + padY) / CANONICAL_HEIGHT,
            right = (optLeft + singleOptWidth - padX) / CANONICAL_WIDTH,
            bottom = (rowBottom - padY) / CANONICAL_HEIGHT
        )
    }
}
