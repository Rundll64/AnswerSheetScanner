package com.local.omrscanner.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.local.omrscanner.model.ExamConfig
import com.local.omrscanner.model.QuestionConfig
import com.local.omrscanner.model.QuestionGradeResult
import com.local.omrscanner.model.ScanRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 本地持久化与 Excel CSV 成绩单导出仓库
 * 支持考试项目管理、扫描结果存储、考生作答修改与错误数据删除
 */
class LocalRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("omr_local_store", Context.MODE_PRIVATE)
    private val recordsFile = File(context.filesDir, "scan_records_store.json")

    // ==================== 考试项目管理 ====================

    fun loadExams(): List<ExamConfig> {
        val raw = prefs.getString("exams_json", null)
        if (raw.isNullOrBlank()) {
            val defaultExam = ExamConfig(
                id = "exam_project_01",
                title = "高一期末标准化测试（A4通用50题）",
                subject = "综合科目",
                activeQuestionCount = 50,
                createdAtMillis = System.currentTimeMillis()
            )
            saveExams(listOf(defaultExam))
            return listOf(defaultExam)
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ExamConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val qArr = obj.optJSONArray("questions") ?: JSONArray()
                val qList = mutableListOf<QuestionConfig>()
                for (j in 0 until qArr.length()) {
                    val qObj = qArr.getJSONObject(j)
                    val optArr = qObj.optJSONArray("correctOptions") ?: JSONArray()
                    val opts = mutableSetOf<String>()
                    for (k in 0 until optArr.length()) {
                        opts.add(optArr.getString(k))
                    }
                    qList.add(
                        QuestionConfig(
                            questionNumber = qObj.getInt("questionNumber"),
                            correctOptions = if (opts.isEmpty()) setOf("A") else opts,
                            fullScore = qObj.optDouble("fullScore", 2.0).toFloat(),
                            partialScore = qObj.optDouble("partialScore", 1.0).toFloat()
                        )
                    )
                }
                list.add(
                    ExamConfig(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        subject = obj.optString("subject", "通用科目"),
                        activeQuestionCount = obj.optInt("activeQuestionCount", 50),
                        studentIdDigits = obj.optInt("studentIdDigits", 8),
                        questions = if (qList.isNotEmpty()) qList else ExamConfig.generateDefaultQuestions(),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        isArchived = obj.optBoolean("isArchived", false)
                    )
                )
            }
            if (list.isEmpty()) {
                val fallback = ExamConfig("exam_project_01", "默认标准化考试", "综合")
                saveExams(listOf(fallback))
                listOf(fallback)
            } else {
                list
            }
        } catch (e: Exception) {
            val fallback = ExamConfig("exam_project_01", "默认标准化考试", "综合")
            listOf(fallback)
        }
    }

    fun saveExams(exams: List<ExamConfig>) {
        val arr = JSONArray()
        for (exam in exams) {
            val obj = JSONObject()
            obj.put("id", exam.id)
            obj.put("title", exam.title)
            obj.put("subject", exam.subject)
            obj.put("activeQuestionCount", exam.activeQuestionCount)
            obj.put("studentIdDigits", exam.studentIdDigits)
            obj.put("createdAtMillis", exam.createdAtMillis)
            obj.put("isArchived", exam.isArchived)

            val qArr = JSONArray()
            for (q in exam.questions) {
                val qObj = JSONObject()
                qObj.put("questionNumber", q.questionNumber)
                qObj.put("fullScore", q.fullScore.toDouble())
                qObj.put("partialScore", q.partialScore.toDouble())
                val optArr = JSONArray()
                q.correctOptions.forEach { optArr.put(it) }
                qObj.put("correctOptions", optArr)
                qArr.put(qObj)
            }
            obj.put("questions", qArr)
            arr.put(obj)
        }
        prefs.edit().putString("exams_json", arr.toString()).apply()
    }

    fun createExam(
        title: String,
        subject: String,
        questionCount: Int,
        singleScore: Float
    ): ExamConfig {
        val newExam = ExamConfig(
            id = "exam_" + UUID.randomUUID().toString().take(8),
            title = title.ifBlank { "新建考试项目" },
            subject = subject.ifBlank { "综合" },
            activeQuestionCount = questionCount.coerceIn(1, 50),
            questions = ExamConfig.generateDefaultQuestions(singleScore),
            createdAtMillis = System.currentTimeMillis()
        )
        val currentList = loadExams().toMutableList()
        currentList.add(0, newExam)
        saveExams(currentList)
        return newExam
    }

    @Synchronized
    fun deleteExam(examId: String) {
        val currentExams = loadExams().filter { it.id != examId }
        saveExams(currentExams)
        clearRecordsForExam(examId)
    }

    // ==================== 阅卷记录存储、修改与删除 ====================

    @Synchronized
    fun loadAllRecords(): List<ScanRecord> {
        if (!recordsFile.exists()) return emptyList()
        return try {
            val content = recordsFile.readText(Charsets.UTF_8)
            if (content.isBlank()) return emptyList()
            val arr = JSONArray(content)
            val list = mutableListOf<ScanRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val resArr = obj.optJSONArray("results") ?: JSONArray()
                val resList = mutableListOf<QuestionGradeResult>()
                for (j in 0 until resArr.length()) {
                    val rObj = resArr.getJSONObject(j)
                    val selArr = rObj.optJSONArray("selectedOptions") ?: JSONArray()
                    val selSet = mutableSetOf<String>()
                    for (k in 0 until selArr.length()) selSet.add(selArr.getString(k))

                    val corArr = rObj.optJSONArray("correctOptions") ?: JSONArray()
                    val corSet = mutableSetOf<String>()
                    for (k in 0 until corArr.length()) corSet.add(corArr.getString(k))

                    resList.add(
                        QuestionGradeResult(
                            questionNumber = rObj.getInt("questionNumber"),
                            selectedOptions = selSet,
                            correctOptions = corSet,
                            earnedScore = rObj.optDouble("earnedScore", 0.0).toFloat(),
                            maxScore = rObj.optDouble("maxScore", 2.0).toFloat(),
                            optionFillRatios = emptyMap(),
                            hasSmudgeWarning = rObj.optBoolean("hasSmudgeWarning", false)
                        )
                    )
                }

                list.add(
                    ScanRecord(
                        id = obj.getString("id"),
                        examId = obj.optString("examId", ""),
                        studentId = obj.optString("studentId", "未知"),
                        objectiveScore = obj.optDouble("objectiveScore", 0.0).toFloat(),
                        subjectiveScore = obj.optDouble("subjectiveScore", 0.0).toFloat(),
                        maxTotalScore = obj.optDouble("maxTotalScore", 0.0).toFloat(),
                        results = resList,
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                        remark = obj.optString("remark", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadRecordsForExam(examId: String): List<ScanRecord> {
        return loadAllRecords().filter { it.examId == examId }
    }

    @Synchronized
    fun saveRecord(record: ScanRecord) {
        val existing = loadAllRecords().toMutableList()
        val index = existing.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            existing[index] = record
        } else {
            existing.add(0, record)
        }
        writeRecordsToFile(existing)
    }

    @Synchronized
    fun updateRecord(record: ScanRecord) {
        val existing = loadAllRecords().toMutableList()
        val index = existing.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            existing[index] = record
            writeRecordsToFile(existing)
        }
    }

    @Synchronized
    fun deleteRecord(recordId: String) {
        val existing = loadAllRecords().filter { it.id != recordId }
        writeRecordsToFile(existing)
    }

    @Synchronized
    fun clearRecordsForExam(examId: String) {
        val existing = loadAllRecords().filter { it.examId != examId }
        writeRecordsToFile(existing)
    }

    private fun writeRecordsToFile(records: List<ScanRecord>) {
        val arr = JSONArray()
        for (rec in records) {
            val obj = JSONObject()
            obj.put("id", rec.id)
            obj.put("examId", rec.examId)
            obj.put("studentId", rec.studentId)
            obj.put("objectiveScore", rec.objectiveScore.toDouble())
            obj.put("subjectiveScore", rec.subjectiveScore.toDouble())
            obj.put("maxTotalScore", rec.maxTotalScore.toDouble())
            obj.put("timestampMillis", rec.timestampMillis)
            obj.put("remark", rec.remark)

            val rArr = JSONArray()
            for (r in rec.results) {
                val rObj = JSONObject()
                rObj.put("questionNumber", r.questionNumber)
                rObj.put("earnedScore", r.earnedScore.toDouble())
                rObj.put("maxScore", r.maxScore.toDouble())
                rObj.put("hasSmudgeWarning", r.hasSmudgeWarning)

                val selArr = JSONArray()
                r.selectedOptions.forEach { selArr.put(it) }
                rObj.put("selectedOptions", selArr)

                val corArr = rObj.optJSONArray("correctOptions") ?: JSONArray()
                r.correctOptions.forEach { corArr.put(it) }
                rObj.put("correctOptions", corArr)

                rArr.put(rObj)
            }
            obj.put("results", rArr)
            arr.put(obj)
        }

        try {
            val tempFile = File(context.filesDir, "scan_records_store.tmp")
            tempFile.writeText(arr.toString(), Charsets.UTF_8)
            tempFile.renameTo(recordsFile)
        } catch (e: Exception) {
            try {
                recordsFile.writeText(arr.toString(), Charsets.UTF_8)
            } catch (ignored: Exception) {}
        }
    }

    // ==================== Excel XLSX 成绩导出与分享 ====================

    /**
     * 导出当前考试所有考生成绩及各题作答明细为原生 Microsoft Excel (.xlsx) 表格文件
     * 使用标准 OpenXML 格式，100% 兼容 Excel、WPS、Numbers，带表头样式与统计汇总
     */
    fun exportRecordsToXlsxAndShare(exam: ExamConfig, records: List<ScanRecord>) {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val safeTitle = exam.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val fileName = "${safeTitle}_成绩单_${sdf.format(Date())}.xlsx"
        val outFile = File(context.filesDir, fileName)

        XlsxExporter.exportToXlsx(outFile, exam, records)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${exam.title} 成绩单")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "导出并分享成绩单 Excel (.xlsx)").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    @Deprecated("已全面升级为原生 XLSX 导出格式", ReplaceWith("exportRecordsToXlsxAndShare(exam, records)"))
    fun exportRecordsToCsvAndShare(exam: ExamConfig, records: List<ScanRecord>) {
        exportRecordsToXlsxAndShare(exam, records)
    }
}
