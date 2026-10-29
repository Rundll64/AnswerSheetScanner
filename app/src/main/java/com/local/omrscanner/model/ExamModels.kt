package com.local.omrscanner.model

data class Point2D(val x: Float, val y: Float)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class QuestionConfig(
    val questionNumber: Int,
    val correctOptions: Set<String> = setOf("A"),
    val fullScore: Float = 2.0f,
    val partialScore: Float = 1.0f
)

data class ExamConfig(
    val id: String,
    val title: String,
    val subject: String = "通用科目",
    val activeQuestionCount: Int = 50,
    val studentIdDigits: Int = 8,
    val questions: List<QuestionConfig> = generateDefaultQuestions(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
) {
    companion object {
        fun generateDefaultQuestions(score: Float = 2.0f): List<QuestionConfig> = (1..50).map { qNum ->
            val defaultAns = when (qNum % 4) {
                1 -> setOf("A")
                2 -> setOf("B")
                3 -> setOf("C")
                else -> setOf("D")
            }
            QuestionConfig(
                questionNumber = qNum,
                correctOptions = defaultAns,
                fullScore = score,
                partialScore = score / 2f
            )
        }
    }

    fun withQuestionCount(newCount: Int): ExamConfig {
        val count = newCount.coerceIn(1, 50)
        val existingMap = questions.associateBy { it.questionNumber }
        val defaultScore = questions.firstOrNull()?.fullScore ?: 2.0f
        val newQuestions = (1..50).map { qNum ->
            existingMap[qNum] ?: QuestionConfig(
                questionNumber = qNum,
                correctOptions = when (qNum % 4) {
                    1 -> setOf("A")
                    2 -> setOf("B")
                    3 -> setOf("C")
                    else -> setOf("D")
                },
                fullScore = defaultScore,
                partialScore = defaultScore / 2f
            )
        }
        return copy(activeQuestionCount = count, questions = newQuestions)
    }
}

data class QuestionGradeResult(
    val questionNumber: Int,
    val selectedOptions: Set<String>,
    val correctOptions: Set<String>,
    val earnedScore: Float,
    val maxScore: Float,
    val optionFillRatios: Map<String, Float> = emptyMap(),
    val hasSmudgeWarning: Boolean = false
) {
    val isFullCorrect: Boolean
        get() = selectedOptions == correctOptions && selectedOptions.isNotEmpty()
    val isPartialCorrect: Boolean
        get() = !isFullCorrect && earnedScore > 0f
}

data class ScanRecord(
    val id: String,
    val examId: String,
    val studentId: String,
    val objectiveScore: Float,
    val subjectiveScore: Float = 0f,
    val maxTotalScore: Float,
    val results: List<QuestionGradeResult>,
    val timestampMillis: Long = System.currentTimeMillis(),
    val remark: String = ""
) {
    val totalScore: Float
        get() = objectiveScore + subjectiveScore
}
