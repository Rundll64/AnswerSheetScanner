# 智能答题卡扫描阅卷系统 🎓

> **说明：** 本程序使用 [Google Antigravity](https://antigravity.google.com)（Google 深度代码助手）协助编写，编者其实并不是太懂 Android 开发 qAq

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)

一款专为学校、教师及考务人员设计的**纯本地离线标准化答题卡扫描阅卷 Android 应用程序**。内置完整的机器视觉 OMR 识别引擎，无需依赖网络或专业阅卷机，直接通过手机/平板相机即可实现毫秒级自动评分、成绩统计、XLSX 导出与标准化答题卡按需生成。

---

## 📲 快速下载

前往 [**Releases 页面**](https://github.com/Rundll64/AnswerSheetScanner/releases/latest) 下载最新版 APK，直接安装到 Android 手机即可使用，无需任何配置。

> 需要在系统设置中允许"安装未知来源应用"。

---

## 🆕 v1.0.1 版本重大升级

1. **智能四角定位与几何质量把关引擎 (`vision`)**：
   - **多维特征候选提取**：连通域结合面积比、长宽比、填充度、白领对比度等 7 项指标，拒绝仅按大小简单取点；
   - **凸四边形剪枝与几何评分**：结合模板长宽比与透视收敛度评分，支持 4 角点锁定与 3 角点出界容错推断；
   - **高精度双线性插值**：采用 8 自由度单应性矩阵（Homography）与双线性插值采样，透视矫正平滑无锯齿；
   - **三级置信度闸门**：高置信度秒级自动批阅，中置信度提示确认，低置信度平滑对接手动微调，杜绝畸变放行。
2. **纯黑白打印 / 复印件完整兼容（三道抗黑白干扰防线）**：
   - **防线 1（4 邻域实心度腐蚀过滤）**：剥离黑白打印件产生的 1~2px 表格细线、括号与空心字母笔画，仅保留 2B 铅笔实心笔迹；
   - **防线 2（滑窗距离高斯衰减）**：采样窗口内缩 20% 并在边缘引入距离衰减权重，彻底防止未涂气泡向外“吸边”到黑色表格线；
   - **防线 3（同题底噪自校准 Baseline Subtraction）**：客观题以同题最小黑度作为印刷字模基线扣除，考号列以 10 位中位数扣除，精准消除深灰复印字模底噪。
3. **印前模板双模化设计**：
   - 答题卡生成器新增**高明度浅珊瑚红 (`#FF8A8A`)**与**纯黑白浅灰 (`#B5B5B5`)**双模色调，黑白打印下自动呈现浅灰，底噪天然降低 75%；
   - 定位黑块周围白边净空区扩大至 1.5 倍边长以上，杜绝表格线侵入定位核。

---

## ✨ 核心功能

### 1. 纯本地离线高效阅卷（100% 隐私安全）
- **零网络依赖**：所有图像处理、透视校正、填涂分析及数据存储均在设备本地完成，成绩数据不上传任何服务器。
- **项目化考务管理**：支持多场考试独立建档、自定义选择题数量（1~50题）、批量分值设置与教师标准样卷扫码自动填答。

### 2. 红盲光学滤镜与黑白双模过滤（Red-Dropout & B&W Filter）
- **专色红盲滤除**：采用中高考专业光学阅读机红盲算法，自动将答题卡红色印刷框线（Pantone 185C）、选项标识 `[A][B][C][D]` 及准考证号栏线完全滤除为白色背景，杜绝红色框线对铅笔填涂的干扰与误判。
- **黑白双模适配**：针对无彩印条件的黑白复印卷，通过形态学 4 邻域实心腐蚀与同题字模底噪扣除，实现石墨笔迹与复印网格的纯算法分离。

### 3. 四角自适应定位校正（Interactive 4-Corner Alignment）
- **抗干扰定位标记识别**：内置实心黑块定位与连通域几何验证算法，强力免疫手机拍摄时的反光、背景杂斑及镜头畸变。
- **手势拖拽 + 像素级方向键微调**：拍照后进入全屏校对界面，支持手指拖拽四角锚点或使用 ⬅️ ⬆️ ⬇️ ➡️ 方向键进行像素级精准微调，确认后一键完成透视矫正与阅卷。
- **局部自适应质心搜索**：自动吸收拍摄偏移与镜头桶形畸变，结合中心距离高斯衰减保障高精度判分。

### 4. 标准化答题卡按需生成（Word / PDF）
- **版式自适应排版**：题目较少时自动收缩选择题区域，为解答题预留充足书写空间。
- **规范选项标识**：选项框内规范采用 `[A]` `[B]` `[C]` `[D]`，排版规范醒目，细体印刷保证黑白打印零底噪。
- **多格式导出**：一键生成可编辑的 Microsoft Word (`.docx`) 文档与标准 A4 尺寸 PDF，支持 5题分组、多道解答题自定义行数、双模颜色等参数。

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
| 智能定位引擎 | 连通域多维特征分析 + 凸四边形剪枝 + 3角点/4角点自适应匹配 |
| 透视校正 | 8自由度单应性矩阵 Homography（高斯消元 + 双线性插值 Bilinear） |
| 抗干扰识别 | 4 邻域实心度腐蚀 + 中心距离高斯衰减 + Baseline Subtraction 底噪自校准 |
| 颜色过滤 | 红盲色度空间滤镜（模拟专业光学阅读机红盲特性）|
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

# 编译 Release 安装包
./gradlew assembleRelease
```

> Windows 用户请使用 `gradlew.bat assembleRelease`

编译输出 APK 位于：
```
app/build/outputs/apk/release/app-release.apk
```

---

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。

---

<p align="center">
  Made with ❤️ and <a href="https://antigravity.google.com">Google Antigravity</a>
</p>
