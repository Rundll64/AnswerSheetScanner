package com.local.omrscanner.omr

import android.graphics.Bitmap
import com.local.omrscanner.model.ExamConfig

/**
 * 内置模拟填涂答题卡生成器：
 * 统一委托至标准考务排版引擎，生成全套中高考标准考务版面的模拟填涂测试卡
 */
object DemoSheetGenerator {

    fun generateSimulatedSheet(
        examConfig: ExamConfig,
        simulatedStudentId: String = "20260815",
        accuracyRate: Float = 0.86f
    ): Bitmap {
        val config = AnswerSheetGenerator.SheetConfig(
            title = examConfig.title,
            subject = examConfig.subject,
            questionCount = examConfig.activeQuestionCount,
            studentIdDigits = examConfig.studentIdDigits,
            optionsCount = 4,
            isPartitioned = true,
            fillDemoMode = true,
            simulatedStudentId = simulatedStudentId
        )
        return AnswerSheetGenerator.generateSheetBitmap(config)
    }
}
