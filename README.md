# 智能答题卡扫描阅卷系统 🎓

> **说明：** 本程序使用 [Google Antigravity](https://antigravity.google.com)（Google 深度代码助手）协助编写，编者其实并不是太懂 Android 开发 qAq

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)

一款专为学校、教师及考务人员设计的**纯本地离线标准化答题卡扫描阅卷 Android 应用程序**。内置完整的机器视觉 OMR 识别引擎，无需依赖网络或专业阅卷机，直接通过手机/平板相机即可实现毫秒级自动评分、成绩统计、XLSX 导出与标准化答题卡按需生成。

说明：本程序使用Google Antigravity协助编写，编者其实并不是太懂  qAq
---

## 📲 快速下载

前往 [**Releases 页面**](https://github.com/Rundll64/AnswerSheetScanner/releases/latest) 下载最新版 APK，直接安装到 Android 手机即可使用，无需任何配置。

> 需要在系统设置中允许"安装未知来源应用"。

---

## ✨ 核心功能

### 1. 纯本地离线高效阅卷（100% 隐私安全）
- **零网络依赖**：所有图像处理、透视校正、填涂分析及数据存储均在设备本地完成，成绩数据不上传任何服务器。
- **项目化考务管理**：支持多场考试独立建档、自定义选择题数量（1~50题）、批量分值设置与教师标准样卷扫码自动填答。

### 2. 红盲光学滤镜（Red-Dropout Filter）
- **专色红盲滤除**：采用中高考专业光学阅读机红盲算法，自动将答题卡红色印刷框线（Pantone 185C）、选项标识 `[A][B][C][D]` 及准考证号栏线完全滤除为白色背景，杜绝红色框线对铅笔填涂的干扰与误判。

### 3. 四角自适应定位校正（Interactive 4-Corner Alignment）
- **抗干扰定位标记识别**：内置高对比度实心黑块定位算法，强力免疫手机拍摄时的反光、背景杂斑及镜头畸变。
- **手势拖拽 + 像素级方向键微调**：拍照后进入全屏校对界面，支持手指拖拽四角锚点或使用 ⬅️ ⬆️ ⬇️ ➡️ 方向键进行像素级精准微调，确认后一键完成透视矫正与阅卷。
- **局部自适应质心搜索**：自动吸收 ±8px 的拍摄偏移与镜头桶形畸变，保障高精度判分。

### 4. 标准化答题卡按需生成（Word / PDF）
- **版式自适应排版**：题目较少时自动收缩选择题区域，为解答题预留充足书写空间。
- **大字号粗体选项标识**：选项框内采用大字号粗体 `[A]` `[B]` `[C]` `[D]`，排版规范醒目，提升学生填涂精准度。
- **多格式导出**：一键生成可编辑的 Microsoft Word (`.docx`) 文档与标准 A4 尺寸 PDF，支持 5题分组、多道解答题自定义行数等参数。

### 5. 在线成绩表格总览与 XLSX 导出
- **全屏数据看板**：在手机上直接查看类 Excel 成绩网格，支持考号搜索、分数排序与筛选。
- **XLSX 格式导出**：一键导出包含考号、总分、客观题得分、主观题加分、各小题作答明细的格式化 `.xlsx` 电子表格。
- **成绩修改与管理**：支持对单条成绩进行考号核对、作答修改、主观题手动加分及删除。

---

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose (Material Design 3) |
| 相机模块 | CameraX |
| OMR 核心算法 | Bradley-Roth 局部自适应二值化（积分图加速）|
| 透视校正 | 8自由度单应性矩阵 Homography（高斯消元 + 双线性插值）|
| 颜色过滤 | 红盲色度空间滤镜（模拟专业光学阅读机红盲特性）|
| 气泡识别 | 局部自适应质心峰值寻优算法 |
| Word 生成 | 纯 Kotlin OpenXML (`.docx`) 渲染器 |
| PDF 生成 | Android 原生 `PdfDocument` 矢量渲染器 |
| 数据存储 | Room 数据库 |
| XLSX 导出 | Apache POI |

---

## 🔧 编译构建（开发者）

### 环境要求
- **Android Studio** Hedgehog 或更新版本
- **JDK 17**
- **Android SDK** API 26+（目标 API 34）

### 编译步骤

```bash
# 克隆仓库
git clone https://github.com/Rundll64/AnswerSheetScanner.git
cd AnswerSheetScanner

# 编译 Debug 版本
./gradlew assembleDebug
```

> Windows 用户请使用 `gradlew.bat assembleDebug`

编译输出 APK 位于：
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📸 界面预览

| 扫描阅卷 | 成绩总览 | 答题卡生成 |
|---------|---------|-----------|
| 四角定位校正与实时预览 | XLSX 导出与数据看板 | Word/PDF 按需定制 |

---

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。

---

<p align="center">
  Made with ❤️ and <a href="https://antigravity.google.com">Google Antigravity</a>
</p>
