package com.local.omrscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.local.omrscanner.data.LocalRepository
import com.local.omrscanner.model.ExamConfig
import com.local.omrscanner.model.Point2D
import com.local.omrscanner.model.QuestionConfig
import com.local.omrscanner.model.QuestionGradeResult
import com.local.omrscanner.model.ScanRecord
import com.local.omrscanner.omr.AnswerSheetGenerator
import com.local.omrscanner.omr.DemoSheetGenerator
import com.local.omrscanner.omr.DocxAnswerSheetGenerator
import com.local.omrscanner.omr.OmrEngine
import com.local.omrscanner.omr.TemplateSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = LocalRepository(applicationContext)
        setContent {
            ModernOmrTheme {
                OmrScannerApp(repository = repository)
            }
        }
    }
}

@Composable
fun ModernOmrTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF1E40AF),         // 现代深宝蓝
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDBEAFE),
        onPrimaryContainer = Color(0xFF1E3A8A),
        secondary = Color(0xFF0F766E),       // 现代翡翠青
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCCFBF1),
        onSecondaryContainer = Color(0xFF115E59),
        surface = Color(0xFFF8FAFC),         // 浅石板灰
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFF1F5F9),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFCBD5E1),
        outlineVariant = Color(0xFFE2E8F0)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmrScannerApp(repository: LocalRepository) {
    val context = LocalContext.current

    // 持久化考试列表与已选当前考试
    var exams by remember { mutableStateOf(repository.loadExams()) }
    var activeExamId by remember { mutableStateOf(exams.firstOrNull()?.id ?: "default") }
    val activeExam = exams.find { it.id == activeExamId } ?: exams.firstOrNull() ?: ExamConfig("default", "标准测试", "综合")

    // 持久化扫描记录（支持随时增删改查）
    var allRecords by remember { mutableStateOf(repository.loadAllRecords()) }
    val activeExamRecords = remember(allRecords, activeExamId) {
        allRecords.filter { it.examId == activeExamId }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateExamDialog by remember { mutableStateOf(false) }
    var showSheetGeneratorDialog by remember { mutableStateOf(false) }

    // 弹窗状态
    var latestScanOutput by remember { mutableStateOf<OmrEngine.ProcessedSheetOutput?>(null) }
    var pendingAlignBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var reviewingStudentRecord by remember { mutableStateOf<ScanRecord?>(null) }
    var recordPendingDelete by remember { mutableStateOf<ScanRecord?>(null) }
    var examPendingDelete by remember { mutableStateOf<ExamConfig?>(null) }
    var examPendingClear by remember { mutableStateOf<ExamConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.DocumentScanner,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "智能答题卡扫描阅卷",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        "当前: ${activeExam.title.take(12)} (${activeExam.activeQuestionCount}题)",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateExamDialog = true }) {
                        Icon(
                            Icons.Default.AddCircleOutline,
                            contentDescription = "新建考试项目",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("答案与设置", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("扫码阅卷", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(badge = {
                            if (allRecords.isNotEmpty()) {
                                Badge { Text("${allRecords.size}") }
                            }
                        }) {
                            Icon(Icons.Default.TableChart, contentDescription = null)
                        }
                    },
                    label = { Text("在线表格与统计", fontSize = 12.sp) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when (selectedTab) {
                0 -> ExamSetupScreen(
                    exam = activeExam,
                    allExams = exams,
                    onSelectExam = { activeExamId = it.id },
                    onUpdateExam = { updated ->
                        val list = exams.map { if (it.id == updated.id) updated else it }
                        exams = list
                        repository.saveExams(list)
                    },
                    onCreateNewExamClick = { showCreateExamDialog = true },
                    onGenerateSheetClick = { showSheetGeneratorDialog = true }
                )
                1 -> CameraScanScreen(
                    exam = activeExam,
                    scannedCount = activeExamRecords.size,
                    onRequestAlignSheet = { bmp ->
                        pendingAlignBitmap = bmp
                    }
                )
                2 -> ProjectsAndStatsScreen(
                    exams = exams,
                    allRecords = allRecords,
                    activeExamId = activeExamId,
                    onSelectActiveExam = { exam ->
                        activeExamId = exam.id
                        Toast.makeText(context, "已将「${exam.title}」设为当前扫码考试", Toast.LENGTH_SHORT).show()
                    },
                    onCreateNewExam = { showCreateExamDialog = true },
                    onDeleteExam = { examPendingDelete = it },
                    onExportXlsx = { exam, records ->
                        if (records.isEmpty()) {
                            Toast.makeText(context, "该考试暂无已录入的成绩记录", Toast.LENGTH_SHORT).show()
                        } else {
                            repository.exportRecordsToXlsxAndShare(exam, records)
                        }
                    },
                    onInspectStudent = { reviewingStudentRecord = it },
                    onRequestDeleteRecord = { recordPendingDelete = it },
                    onRequestClearExamRecords = { examToClear ->
                        examPendingClear = examToClear
                    }
                )
            }

            // 新建考试项目弹窗
            if (showCreateExamDialog) {
                CreateExamDialog(
                    onDismiss = { showCreateExamDialog = false },
                    onConfirm = { title, subject, questionCount, score ->
                        val created = repository.createExam(title, subject, questionCount, score)
                        exams = repository.loadExams()
                        activeExamId = created.id
                        showCreateExamDialog = false
                        Toast.makeText(context, "成功创建考试项目「$title」，已设为当前阅卷", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 答题卡按需定制与生成弹窗（可选学号位数、选项个数、是否分区、导出A4 PDF/图片）
            if (showSheetGeneratorDialog) {
                CustomSheetGeneratorDialog(
                    initialExam = activeExam,
                    onDismiss = { showSheetGeneratorDialog = false },
                    onUseAsMockTestSheet = { testBmp ->
                        showSheetGeneratorDialog = false
                        pendingAlignBitmap = testBmp
                    }
                )
            }

            // 四角自适应定位校正与确认弹窗（解决拍照精度与对齐问题，支持4角自适应并允许手动拖拽微调）
            if (pendingAlignBitmap != null) {
                CornerAlignmentDialog(
                    bitmap = pendingAlignBitmap!!,
                    onConfirm = { confirmedCorners ->
                        val bmp = pendingAlignBitmap!!
                        val output = OmrEngine.processAnswerSheet(
                            sourceBitmap = bmp,
                            examConfig = activeExam,
                            customCorners = confirmedCorners
                        )
                        latestScanOutput = output
                        pendingAlignBitmap = null
                        try {
                            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
                                .startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        } catch (e: Exception) {}
                    },
                    onDismiss = {
                        pendingAlignBitmap = null
                    }
                )
            }

            // 刚扫描完的复核弹窗（支持确认入库保存，或放弃本次扫描不予入库）
            if (latestScanOutput != null) {
                ResultReviewDialog(
                    record = latestScanOutput!!.record,
                    annotatedBitmap = latestScanOutput!!.annotatedBitmap,
                    onSave = { recordToSave ->
                        repository.saveRecord(recordToSave)
                        allRecords = repository.loadAllRecords()
                        latestScanOutput = null
                        Toast.makeText(context, "考号【${recordToSave.studentId}】成绩已成功录入！", Toast.LENGTH_SHORT).show()
                    },
                    onDiscard = {
                        latestScanOutput = null
                        Toast.makeText(context, "已取消入库，未保存本次扫描", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 学生详细作答明细与全项修改弹窗（支持修改考号、选项核错、主观题加分、直接删除记录）
            if (reviewingStudentRecord != null) {
                StudentDetailDialog(
                    record = reviewingStudentRecord!!,
                    exam = exams.find { it.id == reviewingStudentRecord!!.examId } ?: activeExam,
                    onSaveUpdate = { updatedRecord ->
                        repository.updateRecord(updatedRecord)
                        allRecords = repository.loadAllRecords()
                        reviewingStudentRecord = null
                        Toast.makeText(context, "考生成绩与作答修改已成功保存", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteRecord = { recToDelete ->
                        repository.deleteRecord(recToDelete.id)
                        allRecords = repository.loadAllRecords()
                        reviewingStudentRecord = null
                        Toast.makeText(context, "已删除考生【${recToDelete.studentId}】的阅卷记录", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { reviewingStudentRecord = null }
                )
            }

            // 单条记录删除确认弹窗
            if (recordPendingDelete != null) {
                AlertDialog(
                    onDismissRequest = { recordPendingDelete = null },
                    icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFDC2626)) },
                    title = { Text("确认删除考生记录？", fontWeight = FontWeight.Bold) },
                    text = { Text("确定要删除考号【${recordPendingDelete!!.studentId}】（得分：${recordPendingDelete!!.totalScore}分）的记录吗？删除后该卡片将从成绩单中移除。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                val id = recordPendingDelete!!.id
                                val stu = recordPendingDelete!!.studentId
                                repository.deleteRecord(id)
                                allRecords = repository.loadAllRecords()
                                recordPendingDelete = null
                                Toast.makeText(context, "已删除考生【$stu】的记录", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("确定删除")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { recordPendingDelete = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            // 考试项目删除确认弹窗
            if (examPendingDelete != null) {
                AlertDialog(
                    onDismissRequest = { examPendingDelete = null },
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFDC2626)) },
                    title = { Text("确认删除考试项目？", fontWeight = FontWeight.Bold) },
                    text = { Text("确定要删除考试项目「${examPendingDelete!!.title}」吗？该考试下的所有成绩记录将一并清除。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                val delId = examPendingDelete!!.id
                                repository.deleteExam(delId)
                                exams = repository.loadExams()
                                allRecords = repository.loadAllRecords()
                                if (activeExamId == delId) {
                                    activeExamId = exams.firstOrNull()?.id ?: ""
                                }
                                examPendingDelete = null
                                Toast.makeText(context, "考试项目已删除", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("确认删除项目")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { examPendingDelete = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            // 清空本场考试记录确认弹窗
            if (examPendingClear != null) {
                AlertDialog(
                    onDismissRequest = { examPendingClear = null },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626)) },
                    title = { Text("清空本场考试记录？", fontWeight = FontWeight.Bold) },
                    text = { Text("确定要清空「${examPendingClear!!.title}」下的所有已录入成绩吗？清空后数据无法恢复。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                val clearId = examPendingClear!!.id
                                repository.clearRecordsForExam(clearId)
                                allRecords = repository.loadAllRecords()
                                examPendingClear = null
                                Toast.makeText(context, "已清空该考试的所有成绩记录", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("确定清空")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { examPendingClear = null }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

// ==================== TAB 0: 答案录入与题数自定义设置 ====================

@Composable
fun ExamSetupScreen(
    exam: ExamConfig,
    allExams: List<ExamConfig>,
    onSelectExam: (ExamConfig) -> Unit,
    onUpdateExam: (ExamConfig) -> Unit,
    onCreateNewExamClick: () -> Unit,
    onGenerateSheetClick: () -> Unit
) {
    val context = LocalContext.current
    var titleText by remember(exam.id) { mutableStateOf(exam.title) }
    var questionCountInput by remember(exam.id) { mutableStateOf(exam.activeQuestionCount.toString()) }
    var batchSingleScore by remember { mutableStateOf("2.0") }
    var examDropdownExpanded by remember { mutableStateOf(false) }

    val masterSheetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    val extracted = OmrEngine.extractAnswerKeyFromMasterSheet(bmp, exam.activeQuestionCount)
                    val newQuestions = exam.questions.map { q ->
                        val newAns = extracted[q.questionNumber] ?: q.correctOptions
                        q.copy(correctOptions = newAns)
                    }
                    onUpdateExam(exam.copy(questions = newQuestions))
                    Toast.makeText(context, "已从教师样卷成功提取 ${extracted.size} 题标准答案！", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 顶部考试项目下拉切换栏 (节约页面空间，随时切换考试项目)
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { examDropdownExpanded = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "当前设置考试 (点击下拉切换)",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            exam.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "展开选择项目",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = examDropdownExpanded,
                            onDismissRequest = { examDropdownExpanded = false }
                        ) {
                            allExams.forEach { ex ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(ex.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text("${ex.subject} · ${ex.activeQuestionCount}题", fontSize = 11.sp, color = Color.Gray)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (ex.id == exam.id) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (ex.id == exam.id) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    },
                                    onClick = {
                                        onSelectExam(ex)
                                        examDropdownExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("＋ 新建考试项目", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    examDropdownExpanded = false
                                    onCreateNewExamClick()
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCreateNewExamClick, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = "新建项目", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        FilledTonalButton(
                            onClick = onGenerateSheetClick,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("生成答题卡", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 按需定制与生成答题卡入口卡片
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGenerateSheetClick() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Print, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("按需定制与生成答题卡", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("可选学号位数(4/6/8/10位)、选项数(3/4/5项)、5题分区防串行，一键导出A4打印PDF与图片", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 考试参数配置卡片（输入自定义题数与批量分值）
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "考试参数自定义（支持自由输入题数）",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = titleText,
                        onValueChange = {
                            titleText = it
                            onUpdateExam(exam.copy(title = it))
                        },
                        label = { Text("考试名称") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // 自定义题数输入框（满足需求：题数可以自定义，输入数字）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = questionCountInput,
                            onValueChange = { input ->
                                questionCountInput = input.filter { it.isDigit() }
                                val count = questionCountInput.toIntOrNull()
                                if (count != null && count in 1..50) {
                                    onUpdateExam(exam.withQuestionCount(count))
                                }
                            },
                            label = { Text("自定义题数 (1~50题)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            trailingIcon = { Text("题", modifier = Modifier.padding(end = 12.dp), color = Color.Gray) }
                        )

                        // 批量分值设定
                        OutlinedTextField(
                            value = batchSingleScore,
                            onValueChange = { batchSingleScore = it },
                            label = { Text("单题分值") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f),
                            shape = RoundedCornerShape(10.dp),
                            trailingIcon = { Text("分", modifier = Modifier.padding(end = 12.dp), color = Color.Gray) }
                        )
                    }

                    // 常用题量快速选择小药丸
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("快捷预设:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(10, 20, 30, 40, 50).forEach { preset ->
                            val isCurrent = exam.activeQuestionCount == preset
                            SuggestionChip(
                                onClick = {
                                    questionCountInput = preset.toString()
                                    onUpdateExam(exam.withQuestionCount(preset))
                                },
                                label = { Text("${preset}题", fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val sc = batchSingleScore.toFloatOrNull() ?: 2.0f
                                val updatedQ = exam.questions.map { it.copy(fullScore = sc, partialScore = sc / 2f) }
                                onUpdateExam(exam.copy(questions = updatedQ))
                                Toast.makeText(context, "已将每题分值统一批量应用为 $sc 分", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("应用单题分值")
                        }

                        OutlinedButton(
                            onClick = {
                                masterSheetLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("扫样卷自动填标答", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "标准答案录入（共 ${exam.activeQuestionCount} 题，支持单选与多选）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val totalPoints = exam.questions.take(exam.activeQuestionCount).sumOf { it.fullScore.toDouble() }
                Text("客观题总分: ${"%.1f".format(totalPoints)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        // 题号与答案选项设置行
        items(exam.questions.take(exam.activeQuestionCount), key = { it.questionNumber }) { qConfig ->
            QuestionKeyRow(
                qConfig = qConfig,
                onToggleOption = { opt ->
                    val currentSet = qConfig.correctOptions.toMutableSet()
                    if (currentSet.contains(opt)) {
                        if (currentSet.size > 1) currentSet.remove(opt)
                    } else {
                        currentSet.add(opt)
                    }
                    val updatedList = exam.questions.map {
                        if (it.questionNumber == qConfig.questionNumber) it.copy(correctOptions = currentSet) else it
                    }
                    onUpdateExam(exam.copy(questions = updatedList))
                }
            )
        }
    }
}

@Composable
fun QuestionKeyRow(
    qConfig: QuestionConfig,
    onToggleOption: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "%02d".format(qConfig.questionNumber),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${qConfig.fullScore} 分",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TemplateSpec.OPTIONS.forEach { opt ->
                    val isSelected = qConfig.correctOptions.contains(opt)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleOption(opt) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = opt,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 1: 扫码阅卷 ====================

@Composable
fun CameraScanScreen(
    exam: ExamConfig,
    scannedCount: Int,
    onRequestAlignSheet: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    onRequestAlignSheet(bmp)
                }
            }
        }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture
                                )
                            } catch (e: Exception) {}
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("授权开启相机以扫描答题卡")
                    }
                }
            }

            // 现代绿色雷达取景引导框
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padX = size.width * 0.07f
                val padY = size.height * 0.05f
                val boxW = size.width - padX * 2
                val boxH = size.height - padY * 2

                drawRect(
                    color = Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(padX, padY),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 2f)
                )

                val cornerLen = 42f
                val strokeW = 6f
                val cornerColor = Color(0xFF10B981) // 翡翠绿

                // 左上角标
                drawLine(cornerColor, Offset(padX, padY), Offset(padX + cornerLen, padY), strokeW)
                drawLine(cornerColor, Offset(padX, padY), Offset(padX, padY + cornerLen), strokeW)

                // 右上角标
                drawLine(cornerColor, Offset(size.width - padX, padY), Offset(size.width - padX - cornerLen, padY), strokeW)
                drawLine(cornerColor, Offset(size.width - padX, padY), Offset(size.width - padX - cornerLen, padY), strokeW)

                // 左下角标
                drawLine(cornerColor, Offset(padX, size.height - padY), Offset(padX + cornerLen, size.height - padY), strokeW)
                drawLine(cornerColor, Offset(padX, size.height - padY), Offset(padX, size.height - padY - cornerLen), strokeW)

                // 右下角标
                drawLine(cornerColor, Offset(size.width - padX, size.height - padY), Offset(size.width - padX - cornerLen, size.height - padY), strokeW)
                drawLine(cornerColor, Offset(size.width - padX, size.height - padY), Offset(size.width - padX, size.height - padY - cornerLen), strokeW)
            }

            // 顶部浮动考试提示条
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "对准四角黑色定位块 · 当前已扫 $scannedCount 份",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 底部现代操作底座
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            imageCapture.takePicture(
                                cameraExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bmp = imageProxyToBitmap(image)
                                        image.close()
                                        if (bmp != null) {
                                            ContextCompat.getMainExecutor(context).execute {
                                                onRequestAlignSheet(bmp)
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            Toast.makeText(context, "拍照失败，建议使用相册导入或体验卡", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        },
                        modifier = Modifier.weight(1.2f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("拍照立即阅卷", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("相册选图")
                    }
                }

                FilledTonalButton(
                    onClick = {
                        val randomId = "2026%04d".format((1..9999).random())
                        val demoBmp = DemoSheetGenerator.generateSimulatedSheet(
                            examConfig = exam,
                            simulatedStudentId = randomId,
                            accuracyRate = 0.85f
                        )
                        onRequestAlignSheet(demoBmp)
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("生成模拟填涂答题卡并校正（免打印体验）", fontSize = 13.sp)
                }
            }
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = image.imageInfo.rotationDegrees
    return if (rotation != 0) {
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    } else {
        bmp
    }
}

// ==================== TAB 2: 考试项目顶部下拉切换、在线查看表格与 XLSX 导出 ====================

@Composable
fun ProjectsAndStatsScreen(
    exams: List<ExamConfig>,
    allRecords: List<ScanRecord>,
    activeExamId: String,
    onSelectActiveExam: (ExamConfig) -> Unit,
    onCreateNewExam: () -> Unit,
    onDeleteExam: (ExamConfig) -> Unit,
    onExportXlsx: (ExamConfig, List<ScanRecord>) -> Unit,
    onInspectStudent: (ScanRecord) -> Unit,
    onRequestDeleteRecord: (ScanRecord) -> Unit,
    onRequestClearExamRecords: (ExamConfig) -> Unit
) {
    var selectedExamId by remember(activeExamId, exams) {
        mutableStateOf(if (exams.any { it.id == activeExamId }) activeExamId else exams.firstOrNull()?.id ?: "")
    }
    val currentExam = exams.find { it.id == selectedExamId } ?: exams.firstOrNull() ?: ExamConfig("default", "默认考试", "综合")
    val examRecords = remember(allRecords, currentExam.id) {
        allRecords.filter { it.examId == currentExam.id }
    }

    var examDropdownExpanded by remember { mutableStateOf(false) }
    var currentSubTab by remember { mutableIntStateOf(0) } // 0: 在线查看表格, 1: 题目正答率, 2: 成绩花名册

    val totalCount = examRecords.size
    val avgScore = if (examRecords.isEmpty()) 0f else examRecords.map { it.totalScore }.average().toFloat()
    val maxScore = examRecords.maxOfOrNull { it.totalScore } ?: 0f
    val minScore = examRecords.minOfOrNull { it.totalScore } ?: 0f
    val totalMaxPoints = currentExam.questions.take(currentExam.activeQuestionCount).sumOf { it.fullScore.toDouble() }.toFloat()
    val passLine = totalMaxPoints * 0.6f
    val passCount = examRecords.count { it.totalScore >= passLine }
    val passRate = if (totalCount == 0) 0 else (passCount * 100 / totalCount)

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部项目下拉选择栏 (节约大量页面空间，随时切换考试项目)
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 下拉触发框
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { examDropdownExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "当前考试项目 (点击下拉切换)",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        currentExam.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "展开选择项目",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = examDropdownExpanded,
                        onDismissRequest = { examDropdownExpanded = false }
                    ) {
                        exams.forEach { ex ->
                            val recCount = allRecords.count { it.examId == ex.id }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(ex.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            if (ex.id == activeExamId) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFDCFCE7)
                                                ) {
                                                    Text(
                                                        "当前通道",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF166534),
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            "${ex.subject} · ${ex.activeQuestionCount}题 · 已录入 $recCount 份",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (ex.id == currentExam.id) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (ex.id == currentExam.id) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                },
                                onClick = {
                                    selectedExamId = ex.id
                                    examDropdownExpanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("＋ 新建考试项目", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                examDropdownExpanded = false
                                onCreateNewExam()
                            }
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // 项目快捷按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentExam.id != activeExamId) {
                        FilledTonalButton(
                            onClick = { onSelectActiveExam(currentExam) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("激活扫码", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onCreateNewExam, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = "新建项目", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onDeleteExam(currentExam) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "删除当前项目", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 核心统计指标仪表板
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBigCard(title = "参试人数", value = "$totalCount 人", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    MetricBigCard(title = "平均分", value = "%.1f".format(avgScore), color = Color(0xFF0F766E), modifier = Modifier.weight(1f))
                    MetricBigCard(title = "最高/最低", value = "%.0f / %.0f".format(maxScore, minScore), color = Color(0xFFD97706), modifier = Modifier.weight(1.1f))
                    MetricBigCard(title = "及格率", value = "$passRate%", color = Color(0xFF16A34A), modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(6.dp))

                // 导出 XLSX 与 清空记录 快捷操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "${currentExam.subject} · 共 ${currentExam.activeQuestionCount} 题 · 满分 %.0f".format(totalMaxPoints),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (examRecords.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onRequestClearExamRecords(currentExam) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                                border = BorderStroke(1.dp, Color(0xFFFECACA))
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清空本场", fontSize = 11.sp)
                            }
                        }
                        Button(
                            onClick = { onExportXlsx(currentExam, examRecords) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出 XLSX 表格", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3 级子标签切换（在线查看表格 / 各题正答率分析 / 学生成绩花名册）
        TabRow(selectedTabIndex = currentSubTab) {
            Tab(
                selected = currentSubTab == 0,
                onClick = { currentSubTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("在线查看表格", fontWeight = if (currentSubTab == 0) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            )
            Tab(
                selected = currentSubTab == 1,
                onClick = { currentSubTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("题目正答率", fontWeight = if (currentSubTab == 1) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            )
            Tab(
                selected = currentSubTab == 2,
                onClick = { currentSubTab = 2 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("成绩花名册", fontWeight = if (currentSubTab == 2) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            )
        }

        // 内容分流
        when (currentSubTab) {
            0 -> OnlineSpreadsheetView(
                exam = currentExam,
                records = examRecords,
                onExportXlsx = { onExportXlsx(currentExam, examRecords) },
                onInspectStudent = onInspectStudent,
                onDeleteRecord = onRequestDeleteRecord
            )
            1 -> QuestionAccuracyView(
                exam = currentExam,
                records = examRecords
            )
            2 -> StudentRosterListView(
                records = examRecords,
                onInspectStudent = onInspectStudent,
                onDeleteRecord = onRequestDeleteRecord
            )
        }
    }
}

/**
 * 在线查看表格组件（支持关键词实时筛选、全屏沉浸预览、横纵双向滚动、原样展示各题答案与总分）
 */
@Composable
fun OnlineSpreadsheetView(
    exam: ExamConfig,
    records: List<ScanRecord>,
    onExportXlsx: () -> Unit,
    onInspectStudent: (ScanRecord) -> Unit,
    onDeleteRecord: (ScanRecord) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }

    val filteredRecords = remember(records, searchQuery) {
        if (searchQuery.isBlank()) records
        else records.filter { it.studentId.contains(searchQuery.trim()) }
    }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isFullscreen = false }) {
                                Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏")
                            }
                            Text(
                                "${exam.title} - 全屏表格查看",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Button(
                            onClick = onExportXlsx,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出 XLSX", fontSize = 12.sp)
                        }
                    }

                    SpreadsheetTableGrid(
                        exam = exam,
                        records = filteredRecords,
                        allExamRecords = records,
                        onInspectStudent = onInspectStudent,
                        onDeleteRecord = onDeleteRecord,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        // 表格上方搜索与全屏工具栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索考生考号...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )

            Spacer(Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    "共 ${filteredRecords.size} 条",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            OutlinedButton(
                onClick = { isFullscreen = true },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("全屏", fontSize = 11.sp)
            }
        }

        // 表格网格组件
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = Color.White
        ) {
            SpreadsheetTableGrid(
                exam = exam,
                records = filteredRecords,
                allExamRecords = records,
                onInspectStudent = onInspectStudent,
                onDeleteRecord = onDeleteRecord,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 核心表格网格（包含冻结式表头、斑马行交替、各题得分与作答明细、点击整行调出改判弹窗、底部统计汇总行）
 */
@Composable
fun SpreadsheetTableGrid(
    exam: ExamConfig,
    records: List<ScanRecord>,
    allExamRecords: List<ScanRecord>,
    onInspectStudent: (ScanRecord) -> Unit,
    onDeleteRecord: (ScanRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalScrollState = rememberScrollState()
    val qCount = exam.activeQuestionCount
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    val colSeqWidth = 46.dp
    val colStuWidth = 95.dp
    val colTotalWidth = 68.dp
    val colObjWidth = 62.dp
    val colSubWidth = 62.dp
    val colFullWidth = 55.dp
    val colQWidth = 54.dp
    val colTimeWidth = 105.dp
    val colRemarkWidth = 85.dp
    val colActionWidth = 55.dp

    if (records.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.TableChart, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("暂无成绩记录", color = Color.Gray, fontSize = 14.sp)
                Text("请前往【扫码阅卷】识别答题卡后即可在此在线查阅表格", color = Color.LightGray, fontSize = 12.sp)
            }
        }
        return
    }

    Box(modifier = modifier.horizontalScroll(horizontalScrollState)) {
        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            // 表头行
            item {
                Surface(color = Color(0xFF1E40AF)) {
                    Row(modifier = Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                        TableHeadCell("序号", colSeqWidth)
                        TableHeadCell("考生考号", colStuWidth)
                        TableHeadCell("总分", colTotalWidth)
                        TableHeadCell("客观分", colObjWidth)
                        TableHeadCell("主观分", colSubWidth)
                        TableHeadCell("满分", colFullWidth)
                        for (q in 1..qCount) {
                            TableHeadCell("第${q}题", colQWidth)
                        }
                        TableHeadCell("评卷时间", colTimeWidth)
                        TableHeadCell("教师备注", colRemarkWidth)
                        TableHeadCell("操作", colActionWidth)
                    }
                }
            }

            // 数据行
            items(records.withIndex().toList(), key = { it.value.id }) { (idx, rec) ->
                val isEven = idx % 2 == 0
                val rowBg = if (isEven) Color.White else Color(0xFFF8FAFC)
                val passLine = rec.maxTotalScore * 0.6f
                val isPass = rec.totalScore >= passLine
                val resMap = rec.results.associateBy { it.questionNumber }

                Surface(
                    color = rowBg,
                    modifier = Modifier.clickable { onInspectStudent(rec) }
                ) {
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .border(BorderStroke(0.5.dp, Color(0xFFE2E8F0))),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableCell("${idx + 1}", colSeqWidth, color = Color.Gray)
                        TableCell(rec.studentId, colStuWidth, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        TableCell(
                            "%.1f".format(rec.totalScore),
                            colTotalWidth,
                            fontWeight = FontWeight.Bold,
                            color = if (isPass) Color(0xFF16A34A) else Color(0xFFDC2626)
                        )
                        TableCell("%.1f".format(rec.objectiveScore), colObjWidth)
                        TableCell("%.1f".format(rec.subjectiveScore), colSubWidth)
                        TableCell("%.0f".format(rec.maxTotalScore), colFullWidth, color = Color.Gray)

                        for (q in 1..qCount) {
                            val qRes = resMap[q]
                            val ansStr = if (qRes == null || qRes.selectedOptions.isEmpty()) "-" else qRes.selectedOptions.sorted().joinToString("")
                            val isCorrect = qRes?.isFullCorrect == true
                            val cellColor = when {
                                ansStr == "-" -> Color.LightGray
                                isCorrect -> Color(0xFF16A34A)
                                else -> Color(0xFFDC2626)
                            }
                            TableCell(ansStr, colQWidth, color = cellColor, fontWeight = if (isCorrect) FontWeight.Bold else FontWeight.Normal)
                        }

                        TableCell(sdf.format(Date(rec.timestampMillis)), colTimeWidth, fontSize = 11.sp, color = Color.Gray)
                        TableCell(rec.remark.ifBlank { "-" }, colRemarkWidth, fontSize = 11.sp, color = Color.Gray)

                        Box(modifier = Modifier.width(colActionWidth), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { onDeleteRecord(rec) }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // 统计汇总行
            item {
                val avg = if (allExamRecords.isEmpty()) 0.0 else allExamRecords.map { it.totalScore }.average()
                val max = allExamRecords.maxOfOrNull { it.totalScore } ?: 0f
                val min = allExamRecords.minOfOrNull { it.totalScore } ?: 0f
                val passLine = (exam.questions.take(qCount).sumOf { it.fullScore.toDouble() } * 0.6).toFloat()
                val passRate = if (allExamRecords.isEmpty()) 0 else (allExamRecords.count { it.totalScore >= passLine } * 100 / allExamRecords.size)

                Surface(
                    color = Color(0xFFDBEAFE),
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TableCell("汇总", colSeqWidth, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
                        TableCell("共${allExamRecords.size}人", colStuWidth, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
                        TableCell("均:%.1f".format(avg), colTotalWidth, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
                        TableCell("高:%.0f".format(max), colObjWidth, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
                        TableCell("低:%.0f".format(min), colSubWidth, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
                        TableCell("$passRate%及格", colFullWidth, fontWeight = FontWeight.Bold, color = Color(0xFF166534))

                        val remainingWidth = (colQWidth * qCount) + colTimeWidth + colRemarkWidth + colActionWidth
                        Box(modifier = Modifier.width(remainingWidth).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text("在线表格实时计算 · 点击任意数据行可核验修改作答与考号", fontSize = 11.sp, color = Color(0xFF1E3A8A))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TableHeadCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .border(BorderStroke(0.5.dp, Color(0xFF93C5FD))),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.Normal,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .border(BorderStroke(0.5.dp, Color(0xFFE2E8F0))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = color,
            fontWeight = fontWeight,
            fontSize = fontSize,
            maxLines = 1
        )
    }
}

/**
 * 题目正答率深度诊断视图
 */
@Composable
fun QuestionAccuracyView(
    exam: ExamConfig,
    records: List<ScanRecord>
) {
    val totalCount = records.size
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(exam.questions.take(exam.activeQuestionCount), key = { it.questionNumber }) { qCfg ->
            val correctCount = records.count { rec ->
                rec.results.find { it.questionNumber == qCfg.questionNumber }?.isFullCorrect == true
            }
            val accPercent = if (records.isEmpty()) 0 else (correctCount * 100 / records.size)
            val isLowAccuracy = accPercent < 60 && records.isNotEmpty()

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = if (isLowAccuracy) BorderStroke(1.dp, Color(0xFFFCA5A5)) else CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "第 %02d 题".format(qCfg.questionNumber),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    "标答: ${qCfg.correctOptions.sorted().joinToString("")}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "单题满分: ${qCfg.fullScore} 分   |   正确人数: $correctCount / $totalCount",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "$accPercent%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = if (isLowAccuracy) Color(0xFFDC2626) else Color(0xFF16A34A)
                        )
                        if (isLowAccuracy) {
                            Text("高频错题", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 学生花名册清单（支持卡片式查阅、修改作答与考号、单条删除）
 */
@Composable
fun StudentRosterListView(
    records: List<ScanRecord>,
    onInspectStudent: (ScanRecord) -> Unit,
    onDeleteRecord: (ScanRecord) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "提示：点击卡片可查看作答详情、修改考号或核改答案；点击右侧垃圾桶图标可直接删除误录记录。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (records.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("当前考试暂无录入记录，可切换至「扫码阅卷」进行扫描", color = Color.Gray)
                }
            }
        }

        items(records.sortedByDescending { it.totalScore }, key = { it.id }) { rec ->
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInspectStudent(rec) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "考号: ${rec.studentId}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            if (rec.remark.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        "已备注",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            "客观: ${rec.objectiveScore} 分 + 主观: ${rec.subjectiveScore} 分  |  ${sdf.format(Date(rec.timestampMillis))}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${rec.totalScore} 分",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("满分 ${rec.maxTotalScore}", fontSize = 11.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { onDeleteRecord(rec) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "删除记录",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricSmallCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MetricBigCard(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 11.sp, color = Color.Gray)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color)
        }
    }
}

// ==================== 现代弹窗组件 ====================

/**
 * 新建考试项目弹窗（支持自定义题数输入）
 */
@Composable
fun CreateExamDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, subject: String, questionCount: Int, score: Float) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("综合") }
    var countInput by remember { mutableStateOf("30") }
    var scoreInput by remember { mutableStateOf("2.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("新建考试项目", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("考试名称 (如：期中高一物理考试)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("所属科目 (如：物理 / 数学 / 英语)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = countInput,
                        onValueChange = { countInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("题目数 (1~50)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = scoreInput,
                        onValueChange = { scoreInput = it },
                        label = { Text("每题分值") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val c = countInput.toIntOrNull() ?: 30
                    val s = scoreInput.toFloatOrNull() ?: 2.0f
                    onConfirm(title, subject, c, s)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("创建项目")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("取消")
            }
        }
    )
}

/**
 * 答题卡按需定制与生成弹窗（针对手机屏幕优化为宽敞的大屏 Tab 分页与自适应布局）：
 * 1. 学号位数 (4位 / 6位 / 8位 / 10位)
 * 2. 选项个数 (3项[A-C] / 4项[A-D] / 5项[A-E])
 * 3. 是否分区排版 (每5题分区防串行，带分组虚线隔离线)
 * 4. 自定义题数 (1~50题)
 * 5. 生成模式 (纯空白打印版 / 模拟填涂样卷版)
 * 6. 原生导出 A4 打印版 PDF、高清 PNG 图片，自动备份至手机下载目录
 */
@Composable
fun CustomSheetGeneratorDialog(
    initialExam: ExamConfig,
    onDismiss: () -> Unit,
    onUseAsMockTestSheet: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialExam.title) }
    var subject by remember { mutableStateOf(initialExam.subject) }
    var countInput by remember { mutableStateOf(initialExam.activeQuestionCount.toString()) }
    var studentIdDigits by remember { mutableIntStateOf(8) }
    var optionsCount by remember { mutableIntStateOf(4) }
    var isPartitioned by remember { mutableStateOf(true) }
    var fillDemoMode by remember { mutableStateOf(false) }
    var simulatedId by remember { mutableStateOf("20260815") }
    var subjectiveCount by remember { mutableIntStateOf(2) }
    var subjectiveLines by remember { mutableIntStateOf(8) }

    var selectedSubTab by remember { mutableIntStateOf(0) } // 0: ⚙️ 参数定制, 1: 👁️ A4大图预览
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(title, subject, countInput, studentIdDigits, optionsCount, isPartitioned, fillDemoMode, simulatedId, subjectiveCount, subjectiveLines) {
        val qCount = countInput.toIntOrNull()?.coerceIn(1, 50) ?: 30
        val cfg = AnswerSheetGenerator.SheetConfig(
            title = title.ifBlank { "标准化考试答题卡" },
            subject = subject.ifBlank { "综合" },
            questionCount = qCount,
            studentIdDigits = studentIdDigits,
            optionsCount = optionsCount,
            isPartitioned = isPartitioned,
            fillDemoMode = fillDemoMode,
            simulatedStudentId = simulatedId,
            subjectiveCount = subjectiveCount,
            subjectiveLinesPerQuestion = subjectiveLines
        )
        previewBitmap = AnswerSheetGenerator.generateSheetBitmap(cfg)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题栏
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("按需定制与生成标准答题卡", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "A4标准版",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 手机端分页切换 Tab (参数定制 vs A4大图预览，彻底告别左右挤压)
                TabRow(selectedTabIndex = selectedSubTab) {
                    Tab(
                        selected = selectedSubTab == 0,
                        onClick = { selectedSubTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("参数定制 (学号/选项/题数)", fontWeight = if (selectedSubTab == 0) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                    Tab(
                        selected = selectedSubTab == 1,
                        onClick = { selectedSubTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RemoveRedEye, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("A4版面大图预览", fontWeight = if (selectedSubTab == 1) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                }

                // 中间内容主体
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedSubTab == 0) {
                        // 子页面 0: 手机端舒适垂直表单 (无水平挤压)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // 卡片 1: 考试基本信息
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("考试信息与题量", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        OutlinedTextField(
                                            value = title,
                                            onValueChange = { title = it },
                                            label = { Text("答题卡标题 (如：高一期末标准化测试)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp)
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = subject,
                                                onValueChange = { subject = it },
                                                label = { Text("科目") },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            OutlinedTextField(
                                                value = countInput,
                                                onValueChange = { countInput = it.filter { ch -> ch.isDigit() } },
                                                label = { Text("题目数 (1~50)") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text("常用题量:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            listOf(10, 20, 30, 40, 50).forEach { cnt ->
                                                val isCur = countInput == cnt.toString()
                                                SuggestionChip(
                                                    onClick = { countInput = cnt.toString() },
                                                    label = { Text("${cnt}题", fontSize = 11.sp) },
                                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                                        containerColor = if (isCur) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 卡片 2: 准考证号 / 学号位数自由选择
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("准考证号 / 学号位数", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("根据学校考号规则生成手写数字空格与0~9气泡矩阵：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf(
                                                Pair(4, "4位学号"),
                                                Pair(6, "6位学号"),
                                                Pair(8, "8位(推荐)"),
                                                Pair(10, "10位长号")
                                            ).forEach { (d, label) ->
                                                FilterChip(
                                                    selected = studentIdDigits == d,
                                                    onClick = { studentIdDigits = d },
                                                    label = { Text(label, fontSize = 12.sp) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 卡片 3: 每题选项个数与防串行分区
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("选项个数设置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf(
                                                Pair(3, "3项 (A-C)"),
                                                Pair(4, "4项 (A-D 标准)"),
                                                Pair(5, "5项 (A-E)")
                                            ).forEach { (cnt, label) ->
                                                FilterChip(
                                                    selected = optionsCount == cnt,
                                                    onClick = { optionsCount = cnt },
                                                    label = { Text(label, fontSize = 12.sp) }
                                                )
                                            }
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                        // 分区开关
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { isPartitioned = !isPartitioned }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("每5题独立分区防串行", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Text("每5题自动留出加宽组间距并加绘虚线分界线，防止学生看串行", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(checked = isPartitioned, onCheckedChange = { isPartitioned = it })
                                        }
                                    }
                                }
                            }

                            // 卡片 3.5: 解答题（非选择题）自定义设置
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("解答题（非选择题）设置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text(
                                                if (subjectiveCount > 0) "${subjectiveCount}道解答题 · 每题${subjectiveLines}行" else "不设解答题",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text("题数选择（少则自适应多留书写行，多则均分排版）：", fontSize = 12.sp, color = Color.Gray)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(0 to "无解答题", 1 to "1 题", 2 to "2 题", 3 to "3 题", 4 to "4 题").forEach { (sc, label) ->
                                                FilterChip(
                                                    selected = subjectiveCount == sc,
                                                    onClick = { subjectiveCount = sc },
                                                    label = { Text(label, fontSize = 11.5.sp) }
                                                )
                                            }
                                        }

                                        if (subjectiveCount > 0) {
                                            Text("每题预留答题横线行数：", fontSize = 12.sp, color = Color.Gray)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(4 to "4行(简答)", 6 to "6行(常规)", 8 to "8行(标准)", 10 to "10行(大题)", 12 to "12行(作文)").forEach { (ln, label) ->
                                                    FilterChip(
                                                        selected = subjectiveLines == ln,
                                                        onClick = { subjectiveLines = ln },
                                                        label = { Text(label, fontSize = 11.5.sp) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 卡片 4: 生成模式 (纯空白卡 / 模拟填涂样卡)
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("生成模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChip(
                                                selected = !fillDemoMode,
                                                onClick = { fillDemoMode = false },
                                                label = { Text("📄 纯空白卡 (打印发放)", fontSize = 12.sp) }
                                            )
                                            FilterChip(
                                                selected = fillDemoMode,
                                                onClick = { fillDemoMode = true },
                                                label = { Text("✏️ 模拟填涂样卡 (测试)", fontSize = 12.sp) }
                                            )
                                        }

                                        if (fillDemoMode) {
                                            OutlinedTextField(
                                                value = simulatedId,
                                                onValueChange = { simulatedId = it.filter { ch -> ch.isDigit() } },
                                                label = { Text("模拟填涂准考证号") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 卡片 5: 缩略图与快速切换预览
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedSubTab = 1 }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Default.RemoveRedEye, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text("查看 A4 版面大图实时效果", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                Text("已根据当前参数自动排版，点击可全幅清晰预览", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    } else {
                        // 子页面 1: 全屏宽幅高清预览
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "标准 A4 规格（1200×1700高清），四周附带精准定位块，手机摄像头可直接对齐识别。",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (previewBitmap != null) {
                                        Image(
                                            bitmap = previewBitmap!!.asImageBitmap(),
                                            contentDescription = "答题卡大图渲染",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text("正在渲染版面...", color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部固定操作栏（手机端两栏显眼大按钮，绝对不被遮挡）
                Surface(
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 核心导出按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. 导出 A4 打印版 PDF 主按钮 (大绿钮，极度醒目)
                            Button(
                                onClick = {
                                    if (previewBitmap != null) {
                                        val pdfFile = AnswerSheetGenerator.exportToPdf(context, previewBitmap!!, title.ifBlank { "答题卡" })
                                        // 自动备份到手机公共下载目录
                                        AnswerSheetGenerator.saveToPublicDownloads(context, pdfFile, pdfFile.name, "application/pdf")
                                        // 调起系统分享与打印通道
                                        AnswerSheetGenerator.shareExportedFile(context, pdfFile, "application/pdf", "导出并打印 A4 答题卡 (PDF)")
                                        Toast.makeText(context, "A4打印版 PDF 已生成并备份至手机下载目录！已调起系统分享/打印", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导出 A4 打印版 (PDF)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // 2. 导出 Word 可编辑版 (.docx) 按钮 (专业蓝色按钮，支持手机 WPS 与电脑 Office)
                            Button(
                                onClick = {
                                    val qCount = countInput.toIntOrNull()?.coerceIn(1, 50) ?: 30
                                    val cfg = AnswerSheetGenerator.SheetConfig(
                                        title = title.ifBlank { "标准化考试答题卡" },
                                        subject = subject.ifBlank { "综合" },
                                        questionCount = qCount,
                                        studentIdDigits = studentIdDigits,
                                        optionsCount = optionsCount,
                                        isPartitioned = isPartitioned,
                                        fillDemoMode = fillDemoMode,
                                        simulatedStudentId = simulatedId,
                                        subjectiveCount = subjectiveCount,
                                        subjectiveLinesPerQuestion = subjectiveLines
                                    )
                                    val docxFile = AnswerSheetGenerator.exportToDocx(context, cfg)
                                    AnswerSheetGenerator.saveToPublicDownloads(context, docxFile, docxFile.name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                    DocxAnswerSheetGenerator.shareDocxFile(context, docxFile, "导出并打开 Word 答题卡 (.docx)")
                                    Toast.makeText(context, "Word 版 (.docx) 已生成并备份至手机下载目录！已调起系统分享/WPS打开", Toast.LENGTH_LONG).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导出 Word 版 (DOCX)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 如果开启了模拟填涂，提供一键载入测试
                        if (fillDemoMode) {
                            FilledTonalButton(
                                onClick = {
                                    if (previewBitmap != null) onUseAsMockTestSheet(previewBitmap!!)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("将当前生成的测试卡载入系统阅卷", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 学生成绩作答明细与可编辑修改/删除弹窗（满足修改与纠错需求）
 */
@Composable
fun StudentDetailDialog(
    record: ScanRecord,
    exam: ExamConfig,
    onSaveUpdate: (ScanRecord) -> Unit,
    onDeleteRecord: (ScanRecord) -> Unit,
    onDismiss: () -> Unit
) {
    var studentIdInput by remember(record.id) { mutableStateOf(record.studentId) }
    var subjInput by remember(record.id) { mutableStateOf(record.subjectiveScore.toString()) }
    var remarkInput by remember(record.id) { mutableStateOf(record.remark) }

    // 题目作答修改状态（支持教师在弹窗内直接点击纠正或补充涂卡）
    var resultsState by remember(record.id) { mutableStateOf(record.results) }

    // 动态计算修改后的客观题得分
    val qMap = remember(exam.id, exam.questions) { exam.questions.associateBy { it.questionNumber } }
    val currentObjectiveScore = remember(resultsState, qMap) {
        resultsState.sumOf { res ->
            val qCfg = qMap[res.questionNumber]
            if (qCfg != null) {
                when {
                    res.selectedOptions.isEmpty() -> 0.0
                    res.selectedOptions == qCfg.correctOptions -> qCfg.fullScore.toDouble()
                    qCfg.correctOptions.size > 1 && qCfg.correctOptions.containsAll(res.selectedOptions) -> qCfg.partialScore.toDouble()
                    else -> 0.0
                }
            } else 0.0
        }.toFloat()
    }
    val currentSubjScore = subjInput.toFloatOrNull() ?: 0f
    val currentTotalScore = currentObjectiveScore + currentSubjScore

    var showDeleteConfirmInDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("考生作答明细与纠错", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(
                    onClick = { showDeleteConfirmInDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("删除此记录", fontSize = 12.sp)
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("当前总分: $currentTotalScore / ${record.maxTotalScore}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("客观题: $currentObjectiveScore 分", fontSize = 13.sp)
                        }
                    }
                }

                // 考号编辑（如果机器误识别考号，教师可在此直接修改正确考号）
                item {
                    OutlinedTextField(
                        value = studentIdInput,
                        onValueChange = { studentIdInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("考生考号 (可直接修改纠错)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = subjInput,
                            onValueChange = { subjInput = it },
                            label = { Text("主观题加分") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = remarkInput,
                            onValueChange = { remarkInput = it },
                            label = { Text("教师备注") },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                item {
                    Text(
                        "客观题作答选项（点击 A/B/C/D 可人工改判纠错）:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(resultsState, key = { it.questionNumber }) { qRes ->
                    val qCfg = qMap[qRes.questionNumber]
                    val correctSet = qCfg?.correctOptions ?: qRes.correctOptions
                    val isCorrect = qRes.selectedOptions == correctSet && qRes.selectedOptions.isNotEmpty()

                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCorrect) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
                        ),
                        border = BorderStroke(1.dp, if (isCorrect) Color(0xFFBBF7D0) else Color(0xFFFECACA))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("第 %02d 题".format(qRes.questionNumber), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("标答: ${correctSet.sorted().joinToString("")}", fontSize = 11.sp, color = Color.Gray)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TemplateSpec.OPTIONS.forEach { opt ->
                                    val isSelected = qRes.selectedOptions.contains(opt)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray),
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable {
                                                val newSet = qRes.selectedOptions.toMutableSet()
                                                if (newSet.contains(opt)) newSet.remove(opt) else newSet.add(opt)
                                                val newEarned = when {
                                                    newSet.isEmpty() -> 0f
                                                    newSet == correctSet -> qCfg?.fullScore ?: 2f
                                                    correctSet.size > 1 && correctSet.containsAll(newSet) -> qCfg?.partialScore ?: 1f
                                                    else -> 0f
                                                }
                                                val updatedList = resultsState.map {
                                                    if (it.questionNumber == qRes.questionNumber) {
                                                        it.copy(selectedOptions = newSet, earnedScore = newEarned)
                                                    } else it
                                                }
                                                resultsState = updatedList
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = opt,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = record.copy(
                        studentId = studentIdInput.ifBlank { record.studentId },
                        objectiveScore = currentObjectiveScore,
                        subjectiveScore = currentSubjScore,
                        results = resultsState,
                        remark = remarkInput
                    )
                    onSaveUpdate(updated)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("保存全部修改")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("取消")
            }
        }
    )

    if (showDeleteConfirmInDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmInDialog = false },
            title = { Text("确认删除此条记录？") },
            text = { Text("删除后考生【${record.studentId}】的成绩将彻底移除。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmInDialog = false
                        onDeleteRecord(record)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmInDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 实时扫描结果复核弹窗（支持确认入库保存，或放弃本次扫描不予录入）
 */
@Composable
fun ResultReviewDialog(
    record: ScanRecord,
    annotatedBitmap: Bitmap,
    onSave: (ScanRecord) -> Unit,
    onDiscard: () -> Unit
) {
    var subjInput by remember(record.id) { mutableStateOf(record.subjectiveScore.toString()) }

    AlertDialog(
        onDismissRequest = onDiscard,
        title = {
            Text(
                "阅卷结果复核 — 考号: ${record.studentId}",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    val subj = subjInput.toFloatOrNull() ?: 0f
                    Text(
                        "总分: ${record.objectiveScore + subj} / ${record.maxTotalScore} （客观题 ${record.objectiveScore} 分）",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "图例：绿框=正确，红框=错选，蓝框=漏涂标答，黄框=擦除不净",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                item {
                    Image(
                        bitmap = annotatedBitmap.asImageBitmap(),
                        contentDescription = "批改预览图",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 340.dp)
                            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                    )
                }
                item {
                    OutlinedTextField(
                        value = subjInput,
                        onValueChange = { subjInput = it },
                        label = { Text("主观题加分 (选填)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val subj = subjInput.toFloatOrNull() ?: record.subjectiveScore
                    onSave(record.copy(subjectiveScore = subj))
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("确认入库保存")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDiscard,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("放弃本次扫描")
            }
        }
    )
}

/**
 * 四角自适应定位校正与确认界面
 * 解决拍照精度与对齐问题（如平板屏幕反光、倾斜、周边杂物）
 * 1. 自动高精度定位 4 个角标
 * 2. 界面实时呈现 4 角准心与校正框
 * 3. 支持用户直接手指触摸拖动任意角点
 * 4. 提供 [①左上 / ②右上 / ③右下 / ④左下] 快速选中和 [⬅️ ⬆️ ⬇️ ➡️] 像素级方向微调键
 * 5. 用户确认满意后，点击【✅ 确认识别并自动阅卷】
 */
@Composable
fun CornerAlignmentDialog(
    bitmap: Bitmap,
    onConfirm: (List<Point2D>) -> Unit,
    onDismiss: () -> Unit
) {
    // 初始自动识别四角定位点
    var corners by remember(bitmap) {
        mutableStateOf(OmrEngine.autoDetectCorners(bitmap))
    }
    // 当前选中的待微调角点：0: 左上, 1: 右上, 2: 右下, 3: 左下
    var selectedCornerIdx by remember { mutableIntStateOf(0) }
    // 微调步长（每次 3 像素）
    val stepSize = 3f
    val cornerNames = listOf("① 左上", "② 右上", "③ 右下", "④ 左下")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F172A)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部状态栏与重新自适应
                Surface(
                    color = Color(0xFF1E293B),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🎯 四角定位校正与确认",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "请确保4个绿色圆环对齐答题卡黑色定位方块",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.5.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                corners = OmrEngine.autoDetectCorners(bitmap)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8))
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("重新自适应", color = Color(0xFF38BDF8), fontSize = 12.sp)
                        }
                    }
                }

                // 中间大图交互画板 (支持手指触摸拖动四角)
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val viewW = constraints.maxWidth.toFloat()
                    val viewH = constraints.maxHeight.toFloat()
                    val bmpW = bitmap.width.toFloat()
                    val bmpH = bitmap.height.toFloat()

                    val scale = minOf(viewW / bmpW, viewH / bmpH)
                    val renderW = bmpW * scale
                    val renderH = bmpH * scale
                    val offsetX = (viewW - renderW) / 2f
                    val offsetY = (viewH - renderH) / 2f

                    fun toScreen(p: Point2D): Offset =
                        Offset(offsetX + p.x * scale, offsetY + p.y * scale)

                    fun toBitmap(screenOffset: Offset): Point2D {
                        val bx = ((screenOffset.x - offsetX) / scale).coerceIn(0f, bmpW)
                        val by = ((screenOffset.y - offsetY) / scale).coerceIn(0f, bmpH)
                        return Point2D(bx, by)
                    }

                    // 底层：原图
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "答题卡定位图",
                        modifier = Modifier.fillMaxSize()
                    )

                    // 顶层交互 Canvas：捕获拖拽手势并绘制引导框和准心
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bitmap, scale, offsetX, offsetY) {
                                detectDragGestures(
                                    onDragStart = { startPos ->
                                        var closestIdx = -1
                                        var minDistance = Float.MAX_VALUE
                                        corners.forEachIndexed { i, pt ->
                                            val screenPt = toScreen(pt)
                                            val dist = kotlin.math.hypot(
                                                screenPt.x - startPos.x,
                                                screenPt.y - startPos.y
                                            )
                                            if (dist < minDistance) {
                                                minDistance = dist
                                                closestIdx = i
                                            }
                                        }
                                        if (minDistance <= 60.dp.toPx() && closestIdx != -1) {
                                            selectedCornerIdx = closestIdx
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (selectedCornerIdx in 0..3 && corners.size == 4) {
                                            val curPt = corners[selectedCornerIdx]
                                            val curScreen = toScreen(curPt)
                                            val newScreen = Offset(
                                                curScreen.x + dragAmount.x,
                                                curScreen.y + dragAmount.y
                                            )
                                            val newBmpPt = toBitmap(newScreen)
                                            val updated = corners.toMutableList()
                                            updated[selectedCornerIdx] = newBmpPt
                                            corners = updated
                                        }
                                    }
                                )
                            }
                    ) {
                        if (corners.size == 4) {
                            val p0 = toScreen(corners[0]) // TL
                            val p1 = toScreen(corners[1]) // TR
                            val p2 = toScreen(corners[2]) // BR
                            val p3 = toScreen(corners[3]) // BL

                            // 绘制四边形引导框与半透明绿罩
                            val quadPath = Path().apply {
                                moveTo(p0.x, p0.y)
                                lineTo(p1.x, p1.y)
                                lineTo(p2.x, p2.y)
                                lineTo(p3.x, p3.y)
                                close()
                            }
                            drawPath(
                                path = quadPath,
                                color = Color(0x1810B981),
                                style = Fill
                            )
                            drawPath(
                                path = quadPath,
                                color = Color(0xFF10B981),
                                style = Stroke(width = 3.dp.toPx())
                            )

                            // 绘制 4 个定位角标与十字光标
                            corners.forEachIndexed { idx, pt ->
                                val sp = toScreen(pt)
                                val isSelected = idx == selectedCornerIdx
                                val ringColor = if (isSelected) Color(0xFFFACC15) else Color(0xFF10B981)
                                val auraColor = if (isSelected) Color(0x66FACC15) else Color(0x4410B981)

                                drawCircle(
                                    color = auraColor,
                                    radius = if (isSelected) 26.dp.toPx() else 20.dp.toPx(),
                                    center = sp
                                )
                                drawCircle(
                                    color = ringColor,
                                    radius = if (isSelected) 18.dp.toPx() else 14.dp.toPx(),
                                    center = sp,
                                    style = Stroke(width = if (isSelected) 3.5.dp.toPx() else 2.5.dp.toPx())
                                )
                                drawCircle(
                                    color = ringColor,
                                    radius = 3.dp.toPx(),
                                    center = sp
                                )
                                val crossLen = 12.dp.toPx()
                                drawLine(ringColor, Offset(sp.x - crossLen, sp.y), Offset(sp.x + crossLen, sp.y), strokeWidth = 1.5.dp.toPx())
                                drawLine(ringColor, Offset(sp.x, sp.y - crossLen), Offset(sp.x, sp.y + crossLen), strokeWidth = 1.5.dp.toPx())
                            }
                        }
                    }
                }

                // 底部微调控制台与操作按钮
                Surface(
                    color = Color(0xFF1E293B),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 四角快速切换选择
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            cornerNames.forEachIndexed { idx, name ->
                                val isSel = idx == selectedCornerIdx
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Color(0xFFFACC15) else Color(0xFF334155),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedCornerIdx = idx }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            name,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) Color.Black else Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // 方向微调小键盘
                        fun nudge(dx: Float, dy: Float) {
                            if (selectedCornerIdx in 0..3 && corners.size == 4) {
                                val cur = corners[selectedCornerIdx]
                                val updated = corners.toMutableList()
                                updated[selectedCornerIdx] = Point2D(
                                    (cur.x + dx).coerceIn(0f, bitmap.width.toFloat()),
                                    (cur.y + dy).coerceIn(0f, bitmap.height.toFloat())
                                )
                                corners = updated
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "调整: ",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                                Text(
                                    cornerNames.getOrElse(selectedCornerIdx) { "" },
                                    color = Color(0xFFFACC15),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { nudge(-stepSize, 0f) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("⬅️ 左", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { nudge(0f, -stepSize) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("⬆️ 上", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { nudge(0f, stepSize) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("⬇️ 下", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { nudge(stepSize, 0f) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("➡️ 右", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }

                        // 确认与取消按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF64748B))
                            ) {
                                Text("取消重拍", color = Color(0xFFCBD5E1))
                            }

                            Button(
                                onClick = { onConfirm(corners) },
                                modifier = Modifier
                                    .weight(1.8f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981)
                                )
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "确认识别并自动阅卷",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
