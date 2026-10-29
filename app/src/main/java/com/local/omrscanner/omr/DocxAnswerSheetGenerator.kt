package com.local.omrscanner.omr

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 轻量级 Microsoft Word (.docx) OpenXML 答题卡生成器：
 * 参照全国中高考、大学四六级及统考答题卡标准排版规范：
 * 1. 经典考务红标风格边框与标题
 * 2. 考生信息栏与准考证号填涂表
 * 3. 客观选择题作答区：自动自适应列数，选项标准括号格式 [A] [B] [C] [D]，5题防串行分组
 * 4. 解答题（非选择题）作答区：自适应题数与自适应书写横线行数
 * 5. 100% 兼容 Microsoft Office Word, WPS Office 手机版与电脑版, 微信/QQ 在线文档预览
 */
object DocxAnswerSheetGenerator {

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        zos.write(bytes, 0, bytes.size)
        zos.closeEntry()
    }

    fun exportToDocx(context: Context, config: AnswerSheetGenerator.SheetConfig): File {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val safeTitle = config.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val outFile = File(context.filesDir, "${safeTitle}_标准答题卡_${sdf.format(Date())}.docx")

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            // 1. [Content_Types].xml
            writeZipEntry(
                zos,
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""
            )

            // 2. _rels/.rels
            writeZipEntry(
                zos,
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
            )

            // 3. word/_rels/document.xml.rels
            writeZipEntry(
                zos,
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )

            // 4. word/styles.xml
            writeZipEntry(
                zos,
                "word/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Microsoft YaHei" w:eastAsia="Microsoft YaHei" w:hAnsi="Microsoft YaHei"/>
        <w:sz w:val="20"/>
        <w:color w:val="0F172A"/>
      </w:rPr>
    </w:rPrDefault>
  </w:docDefaults>
</w:styles>"""
            )

            // 5. word/document.xml
            val docXml = buildDocumentXml(config)
            writeZipEntry(zos, "word/document.xml", docXml)
        }

        return outFile
    }

    private fun buildDocumentXml(config: AnswerSheetGenerator.SheetConfig): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        sb.append("<w:body>")

        val safeTitle = escapeXml(config.title)
        val safeSubject = escapeXml(config.subject)
        val qCount = config.questionCount.coerceIn(1, 50)
        val digits = config.studentIdDigits.coerceIn(4, 10)
        val subjCount = config.subjectiveCount.coerceIn(0, 5)
        val subjLines = config.subjectiveLinesPerQuestion.coerceIn(2, 20)

        // 1. 考卷主标题
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"60\" w:after=\"40\"/></w:pPr>")
        sb.append("""<w:r><w:rPr><w:b/><w:sz w:val="32"/><w:color w:val="B91C1C"/></w:rPr><w:t>■ $safeTitle · 答题卡 ■</w:t></w:r>""")
        sb.append("</w:p>")

        // 2. 副标题与统计
        val partText = if (config.isPartitioned) "【5题分组防串行规范版】" else "【标准版】"
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"40\"/></w:pPr>")
        sb.append("""<w:r><w:rPr><w:b/><w:sz w:val="18"/><w:color w:val="334155"/></w:rPr><w:t>科目: $safeSubject    |    选择题: 共 $qCount 题    |    准考证号: $digits 位 $partText</w:t></w:r>""")
        sb.append("</w:p>")

        // 3. 考务警示
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"80\"/></w:pPr>")
        sb.append("""<w:r><w:rPr><w:sz w:val="16"/><w:color w:val="B91C1C"/></w:rPr><w:t>考生注意：请在各题目的答题区域内作答，超出红色矩形边框限定区域的答案无效</w:t></w:r>""")
        sb.append("</w:p>")

        // 4. 考生信息与准考证号表格
        sb.append("""<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="10500"/><w:jc w:val="center"/>""")
        sb.append("""<w:tblBorders><w:top w:val="single" w:sz="6" w:color="B91C1C"/><w:bottom w:val="single" w:sz="6" w:color="B91C1C"/><w:left w:val="single" w:sz="6" w:color="B91C1C"/><w:right w:val="single" w:sz="6" w:color="B91C1C"/><w:insideH w:val="single" w:sz="4" w:color="E2E8F0"/><w:insideV w:val="single" w:sz="4" w:color="E2E8F0"/></w:tblBorders></w:tblPr>""")

        sb.append("<w:tr>")
        // 左列：考生手写信息与注意事项
        sb.append("""<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="5800"/><w:tcMar><w:top w:w="60" w:type="dxa"/><w:bottom w:w="60" w:type="dxa"/><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tcMar></w:tcPr>""")
        sb.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>姓    名：__________________   所属班级：__________________</w:t></w:r></w:p>")
        sb.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>考场/座号：________ / ________   正确填涂：[■]   错误填涂：[√] [×]</w:t></w:r></w:p>")
        sb.append("<w:p><w:r><w:rPr><w:color w:val=\"64748B\"/><w:sz w:val=\"16\"/></w:rPr><w:t>注意事项：选择题必须用 2B 铅笔规范填涂；严禁折叠、破损；超出区域无效。</w:t></w:r></w:p>")
        sb.append("</w:tc>")

        // 右列：准考证号填涂区
        sb.append("""<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="4700"/><w:shd w:fill="FEF2F2"/><w:tcMar><w:top w:w="60" w:type="dxa"/><w:bottom w:w="60" w:type="dxa"/><w:left w:w="60" w:type="dxa"/><w:right w:w="60" w:type="dxa"/></w:tcMar></w:tcPr>""")
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"B91C1C\"/></w:rPr><w:t>准考证号 / 学号填涂区</w:t></w:r></w:p>")
        val idBoxes = (1..digits).joinToString("  ") { "[   ]" }
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>$idBoxes</w:t></w:r></w:p>")
        for (d in 0..9 step 2) {
            val lineStr = (1..digits).joinToString(" ") { "[$d][${d + 1}]" }
            sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"20\"/><w:color w:val=\"B91C1C\"/></w:rPr><w:t>$lineStr</w:t></w:r></w:p>")
        }
        sb.append("</w:tc>")
        sb.append("</w:tr></w:tbl>")

        // 5. 第 Ⅰ 卷 客观选择题作答区
        sb.append("<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"60\"/></w:pPr>")
        sb.append("""<w:r><w:rPr><w:b/><w:sz w:val="22"/><w:color w:val="B91C1C"/></w:rPr><w:t>第 Ⅰ 卷  选择题作答区（共 $qCount 题，请用 2B 铅笔将对应选项框涂黑）</w:t></w:r>""")
        sb.append("</w:p>")

        // 根据题目数量自适应排布列数（少则1~2列，多则3~5列，绝不浪费空间）
        val numCols = when {
            qCount <= 10 -> 1
            qCount <= 20 -> 2
            qCount <= 30 -> 3
            qCount <= 40 -> 4
            else -> 5
        }
        val colWidth = 10500 / numCols
        val optLetters = when (config.optionsCount) {
            3 -> listOf("A", "B", "C")
            5 -> listOf("A", "B", "C", "D", "E")
            else -> listOf("A", "B", "C", "D")
        }
        val optBracketString = optLetters.joinToString("   ") { "[$it]" } // 明确带有 [A] [B] [C] [D] 括号！

        sb.append("""<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="10500"/><w:jc w:val="center"/>""")
        sb.append("""<w:tblBorders><w:top w:val="single" w:sz="6" w:color="B91C1C"/><w:bottom w:val="single" w:sz="6" w:color="B91C1C"/><w:left w:val="single" w:sz="6" w:color="B91C1C"/><w:right w:val="single" w:sz="6" w:color="B91C1C"/><w:insideH w:val="single" w:sz="4" w:color="E2E8F0"/><w:insideV w:val="single" w:sz="4" w:color="B91C1C"/></w:tblBorders></w:tblPr>""")

        // 表头行
        sb.append("<w:tr>")
        for (c in 0 until numCols) {
            val st = c * 10 + 1
            val en = minOf((c + 1) * 10, qCount)
            sb.append("""<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="$colWidth"/><w:shd w:fill="FEF2F2"/><w:vAlign w:val="center"/></w:tcPr>""")
            sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"18\"/><w:color w:val=\"B91C1C\"/></w:rPr><w:t>第 %02d ~ %02d 题</w:t></w:r></w:p></w:tc>".format(st, en))
        }
        sb.append("</w:tr>")

        // 题目数据行（每列10题，5题分组防串行）
        for (r in 0 until 10) {
            sb.append("<w:tr>")
            for (c in 0 until numCols) {
                val qn = c * 10 + r + 1
                sb.append("""<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="$colWidth"/><w:vAlign w:val="center"/><w:tcMar><w:top w:w="30" w:type="dxa"/><w:bottom w:w="30" w:type="dxa"/><w:left w:w="40" w:type="dxa"/><w:right w:w="40" w:type="dxa"/></w:tcMar></w:tcPr>""")
                if (qn <= qCount) {
                    sb.append("<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"B91C1C\"/></w:rPr><w:t>%02d </w:t></w:r><w:r><w:rPr><w:b/><w:color w:val=\"B91C1C\"/><w:sz w:val=\"24\"/></w:rPr><w:t> %s</w:t></w:r></w:p>".format(qn, optBracketString))
                } else {
                    sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:color w:val=\"CBD5E1\"/></w:rPr><w:t>---------------------</w:t></w:r></w:p>")
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")

            // 5题一组防串行提示行
            if (config.isPartitioned && r == 4) {
                sb.append("<w:tr>")
                for (c in 0 until numCols) {
                    sb.append("""<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="$colWidth"/><w:shd w:fill="FFF1F2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:sz w:val="12"/><w:color w:val="E11D48"/></w:rPr><w:t>▲ 5题分组防串行线 ▲</w:t></w:r></w:p></w:tc>""")
                }
                sb.append("</w:tr>")
            }
        }
        sb.append("</w:tbl>")

        // 6. 第 Ⅱ 卷 非选择题（解答题）作答区（按用户自定义题数与行数生成）
        if (subjCount > 0) {
            sb.append("<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"60\"/></w:pPr>")
            sb.append("""<w:r><w:rPr><w:b/><w:sz w:val="22"/><w:color w:val="B91C1C"/></w:rPr><w:t>第 Ⅱ 卷  非选择题作答区（请用 0.5 毫米黑色墨水签字笔在各题规定区域内作答）</w:t></w:r>""")
            sb.append("</w:p>")

            // 教师评分统分表
            sb.append("""<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="10500"/><w:jc w:val="center"/><w:tblBorders><w:top w:val="single" w:sz="4" w:color="B91C1C"/><w:bottom w:val="single" w:sz="4" w:color="B91C1C"/><w:left w:val="single" w:sz="4" w:color="B91C1C"/><w:right w:val="single" w:sz="4" w:color="B91C1C"/><w:insideH w:val="single" w:sz="4" w:color="E2E8F0"/><w:insideV w:val="single" w:sz="4" w:color="B91C1C"/></w:tblBorders></w:tblPr>""")
            sb.append("<w:tr>")
            sb.append("""<w:tc><w:tcPr><w:shd w:fill="FEF2F2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="B91C1C"/></w:rPr><w:t>题号</w:t></w:r></w:p></w:tc>""")
            sb.append("""<w:tc><w:tcPr><w:shd w:fill="FEF2F2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="B91C1C"/></w:rPr><w:t>一(客观)</w:t></w:r></w:p></w:tc>""")
            for (si in 0 until subjCount) {
                val qn = qCount + si + 1
                sb.append("""<w:tc><w:tcPr><w:shd w:fill="FEF2F2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="B91C1C"/></w:rPr><w:t>${qn}题</w:t></w:r></w:p></w:tc>""")
            }
            sb.append("""<w:tc><w:tcPr><w:shd w:fill="FEF2F2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="B91C1C"/></w:rPr><w:t>总分</w:t></w:r></w:p></w:tc>""")
            sb.append("""<w:tc><w:tcPr><w:shd w:fill="FEF2F2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="B91C1C"/></w:rPr><w:t>评卷人</w:t></w:r></w:p></w:tc>""")
            sb.append("</w:tr>")

            // 得分数值行
            sb.append("<w:tr>")
            sb.append("<w:tc><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>得分</w:t></w:r></w:p></w:tc>")
            for (i in 0 until subjCount + 3) {
                sb.append("<w:tc><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t></w:t></w:r></w:p></w:tc>")
            }
            sb.append("</w:tr></w:tbl>")

            // 自定义解答题大框与手写行
            for (si in 0 until subjCount) {
                val qn = qCount + si + 1
                val score = 10 + si * 5
                sb.append("<w:p><w:pPr><w:spacing w:before=\"100\" w:after=\"40\"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"B91C1C\"/></w:rPr><w:t>第 ${qn} 题. 综合解答题（${score}分）</w:t></w:r></w:p>")

                sb.append("""<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="10500"/><w:jc w:val="center"/><w:tblBorders><w:top w:val="single" w:sz="6" w:color="B91C1C"/><w:bottom w:val="single" w:sz="6" w:color="B91C1C"/><w:left w:val="single" w:sz="6" w:color="B91C1C"/><w:right w:val="single" w:sz="6" w:color="B91C1C"/><w:insideH w:val="single" w:sz="4" w:color="E2E8F0"/><w:insideV w:val="none"/></w:tblBorders></w:tblPr>""")
                for (li in 0 until subjLines) {
                    sb.append("<w:tr>")
                    sb.append("""<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="10500"/><w:tcMar><w:top w:w="80" w:type="dxa"/><w:bottom w:w="80" w:type="dxa"/><w:left w:w="60" w:type="dxa"/><w:right w:w="60" w:type="dxa"/></w:tcMar></w:tcPr>""")
                    val hint = if (li == 0) "（请在此区域内规范作答，超出红色矩形边框限定区域书写的内容无效）" else ""
                    sb.append("<w:p><w:r><w:rPr><w:sz w:val=\"14\"/><w:color w:val=\"CBD5E1\"/></w:rPr><w:t>$hint</w:t></w:r></w:p></w:tc>")
                    sb.append("</w:tr>")
                }
                sb.append("</w:tbl>")
            }
        }

        // 7. 底部定位与防伪说明
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"120\" w:after=\"40\"/></w:pPr>")
        sb.append("""<w:r><w:rPr><w:sz w:val="14"/><w:color w:val="64748B"/></w:rPr><w:t>■▲ 底部定位基准区（请保持卡面平整，拍照扫描时完整纳入取景框内） ▲■</w:t></w:r>""")
        sb.append("</w:p>")

        // A4 页面标准尺寸（210mm x 297mm）与页边距
        sb.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="600" w:right="600" w:bottom="600" w:left="600"/></w:sectPr>""")
        sb.append("</w:body></w:document>")

        return sb.toString()
    }

    /**
     * 调用系统分享 / 打开 Word 文件（兼容 WPS、Microsoft Word、微信/QQ发送）
     */
    fun shareDocxFile(context: Context, file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
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
