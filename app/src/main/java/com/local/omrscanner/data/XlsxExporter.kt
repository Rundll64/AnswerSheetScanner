package com.local.omrscanner.data

import com.local.omrscanner.model.ExamConfig
import com.local.omrscanner.model.ScanRecord
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 轻量级 Microsoft Excel (.xlsx) OpenXML 成绩单生成器
 * 无需臃肿外部第三方依赖，生成完全合规的标准 OpenXML 表格，
 * 100% 兼容 Microsoft Excel, WPS Office, Apple Numbers, 腾讯文档, 微信/QQ 表格预览器
 */
object XlsxExporter {

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun getColumnLetter(colIndex: Int): String {
        var c = colIndex
        val sb = StringBuilder()
        while (c >= 0) {
            sb.insert(0, ('A'.code + (c % 26)).toChar())
            c = (c / 26) - 1
        }
        return sb.toString()
    }

    fun exportToXlsx(outFile: File, exam: ExamConfig, records: List<ScanRecord>) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            // 1. [Content_Types].xml
            writeEntry(
                zos,
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
            )

            // 2. _rels/.rels
            writeEntry(
                zos,
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )

            // 3. xl/_rels/workbook.xml.rels
            writeEntry(
                zos,
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )

            // 4. xl/workbook.xml
            val safeSheetTitle = escapeXml(exam.title.take(28).ifBlank { "成绩单" })
            writeEntry(
                zos,
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$safeSheetTitle" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
            )

            // 5. xl/styles.xml
            writeEntry(
                zos,
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="3">
    <font><sz val="11"/><color rgb="FF1F2937"/><name val="Microsoft YaHei"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Microsoft YaHei"/></font>
    <font><b/><sz val="11"/><color rgb="FF1E40AF"/><name val="Microsoft YaHei"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1E40AF"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF8FAFC"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="5">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0"/>
    <!-- s=1: 表头 蓝底白字加粗居中 -->
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <!-- s=2: 普通白底居中 -->
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <!-- s=3: 斑马浅灰底居中 -->
    <xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <!-- s=4: 汇总统计行加粗 -->
    <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
  </cellXfs>
</styleSheet>"""
            )

            // 6. xl/worksheets/sheet1.xml
            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val qCount = exam.activeQuestionCount

            val sheetSb = StringBuilder()
            sheetSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView tabSelected="1" workbookViewId="0"/></sheetViews>
<sheetFormatPr defaultRowHeight="22"/>
""")

            // 列宽设置
            val totalCols = 6 + (qCount * 2) + 2
            sheetSb.append("<cols>")
            sheetSb.append("""<col min="1" max="1" width="8" customWidth="1"/>""")
            sheetSb.append("""<col min="2" max="2" width="16" customWidth="1"/>""")
            sheetSb.append("""<col min="3" max="5" width="12" customWidth="1"/>""")
            sheetSb.append("""<col min="6" max="6" width="10" customWidth="1"/>""")
            if (qCount > 0) {
                sheetSb.append("""<col min="7" max="${6 + qCount * 2}" width="12" customWidth="1"/>""")
            }
            sheetSb.append("""<col min="${totalCols - 1}" max="${totalCols - 1}" width="18" customWidth="1"/>""")
            sheetSb.append("""<col min="${totalCols}" max="${totalCols}" width="22" customWidth="1"/>""")
            sheetSb.append("</cols>")

            sheetSb.append("<sheetData>")

            // 第 1 行：表头
            var rowIndex = 1
            sheetSb.append("""<row r="$rowIndex" ht="26" customHeight="1">""")
            val headers = mutableListOf("序号", "考生考号", "卷面总分", "客观题得分", "主观题得分", "考试满分")
            for (q in 1..qCount) {
                headers.add("第${q}题作答")
                headers.add("第${q}题得分")
            }
            headers.add("评卷时间")
            headers.add("教师备注")

            for (colIdx in headers.indices) {
                val ref = "${getColumnLetter(colIdx)}$rowIndex"
                sheetSb.append("""<c r="$ref" t="inlineStr" s="1"><is><t>${escapeXml(headers[colIdx])}</t></is></c>""")
            }
            sheetSb.append("</row>")

            // 数据行
            for ((idx, rec) in records.withIndex()) {
                rowIndex++
                val styleIdx = if (idx % 2 == 0) 2 else 3
                sheetSb.append("""<row r="$rowIndex" ht="22" customHeight="1">""")

                var colIdx = 0
                fun addCellStr(valStr: String, s: Int = styleIdx) {
                    val ref = "${getColumnLetter(colIdx)}$rowIndex"
                    sheetSb.append("""<c r="$ref" t="inlineStr" s="$s"><is><t>${escapeXml(valStr)}</t></is></c>""")
                    colIdx++
                }
                fun addCellNum(valNum: Double, s: Int = styleIdx) {
                    val ref = "${getColumnLetter(colIdx)}$rowIndex"
                    sheetSb.append("""<c r="$ref" s="$s"><v>$valNum</v></c>""")
                    colIdx++
                }

                // 序号
                addCellNum((idx + 1).toDouble())
                // 考号（文本，保留可能存在的前导0）
                addCellStr(rec.studentId)
                // 总分
                addCellNum(rec.totalScore.toDouble())
                // 客观分
                addCellNum(rec.objectiveScore.toDouble())
                // 主观分
                addCellNum(rec.subjectiveScore.toDouble())
                // 满分
                addCellNum(rec.maxTotalScore.toDouble())

                // 各题作答与得分
                val resMap = rec.results.associateBy { it.questionNumber }
                for (q in 1..qCount) {
                    val qRes = resMap[q]
                    val ansStr = if (qRes == null || qRes.selectedOptions.isEmpty()) "未涂" else qRes.selectedOptions.sorted().joinToString("")
                    val earned = qRes?.earnedScore?.toDouble() ?: 0.0
                    addCellStr(ansStr)
                    addCellNum(earned)
                }

                // 时间
                addCellStr(timeFmt.format(Date(rec.timestampMillis)))
                // 备注
                addCellStr(rec.remark)

                sheetSb.append("</row>")
            }

            // 统计汇总行 (如果有记录)
            if (records.isNotEmpty()) {
                rowIndex++
                val avg = records.map { it.totalScore }.average()
                val max = records.maxOfOrNull { it.totalScore } ?: 0f
                val min = records.minOfOrNull { it.totalScore } ?: 0f
                val passLine = (exam.questions.take(qCount).sumOf { it.fullScore.toDouble() } * 0.6).toFloat()
                val passRate = (records.count { it.totalScore >= passLine } * 100 / records.size)

                sheetSb.append("""<row r="$rowIndex" ht="26" customHeight="1">""")
                var colIdx = 0
                fun addSummaryStr(valStr: String) {
                    val ref = "${getColumnLetter(colIdx)}$rowIndex"
                    sheetSb.append("""<c r="$ref" t="inlineStr" s="4"><is><t>${escapeXml(valStr)}</t></is></c>""")
                    colIdx++
                }

                addSummaryStr("汇总")
                addSummaryStr("参试共 ${records.size} 人")
                addSummaryStr("均分: %.1f".format(avg))
                addSummaryStr("最高: %.1f".format(max.toDouble()))
                addSummaryStr("最低: %.1f".format(min.toDouble()))
                addSummaryStr("及格率: $passRate%")

                while (colIdx < totalCols) {
                    val ref = "${getColumnLetter(colIdx)}$rowIndex"
                    sheetSb.append("""<c r="$ref" t="inlineStr" s="4"><is><t>-</t></is></c>""")
                    colIdx++
                }

                sheetSb.append("</row>")
            }

            sheetSb.append("</sheetData></worksheet>")

            writeEntry(zos, "xl/worksheets/sheet1.xml", sheetSb.toString())
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }
}
