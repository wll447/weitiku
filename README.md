# 魏题库 WeiTiku

一个**无广告、无会员、无登录**的 Android 搜题 + 练题应用。题库自己导入，数据全在本机，不联网也能用。

> **遇到问题？** 欢迎到 [Issues](https://github.com/wll447/weitiku/issues) 反馈 —— 报 bug 时请尽量附上**机型**和**截图**（部分机型日志受限，截图最可靠）。


## 功能

- **扫描搜题**：相机取景，识别区可拖动 / 拖角缩放 / 双指捏合；识别到的文字框在预览上高亮标注，认没认对一眼可见
- **拍照搜题**：取景时**实时自动框住题目**（框跟着题目走），拍下即按该框裁剪；也可手动拖角微调或一键选整页
- **文本搜题**：粘贴题干，本地题库检索
- **录屏搜题**：MediaProjection 抓屏 + 悬浮面板（实验性；需授权录屏与悬浮窗）
- **练题**：顺序 / 随机 / 模拟考试，错题本、收藏、清空记录；单选、多选、判断、问答都支持
- **题库导入**：Excel（.xlsx / .xls）、CSV、TXT。**按表头自动识别列**（题干 / 答案 / 选项A~G / 解析 / 题型），列顺序随意，无关列自动忽略
- **导入即标准化**：自动去水印（来源 / 出题 / 推荐）、剥题号与题型标签、全角半角归一、选项去前缀、答案规整（`b`→`B`、`对`→`A`、`1`→`A`），已有题库可一键重新加工
- **云端 AI（可选，默认关闭）**：本地搜不到时调用视觉模型做结构化识别与作答；练题页可要 AI 解析。Key 存本机，不进代码

## 技术要点

| 模块 | 做法 |
|---|---|
| 本地检索 | 倒排索引 + TF-IDF（`tf × log10(N/(df+1))`）；两段式：先按原序快筛，再对候选做**选项全排列**精算（解决同一题在不同题库选项顺序不同）；LCS 二次校验 |
| OCR | PP-OCRv4（ONNX Runtime），跑在 **`:ocr` 独立进程**，主进程通过 AIDL 调用，带进程内回退 |
| 版面/框选 | 版面模型 `layout_cdla`（ONNX）+ 几何聚簇：按纵向相邻把行框聚簇、按最长行/面积选题目块 |
| 相机 | CameraX（Preview + ImageAnalysis + ImageCapture），实时检测每 ~700ms 一帧，只跑检测不跑识别 |
| 录屏 | MediaProjection + 前台服务 + `TYPE_APPLICATION_OVERLAY` 悬浮窗 |
| 架构 | Kotlin + ViewBinding，minSdk 26 / targetSdk 34 |

## 构建

环境：JDK 17、Android SDK（compileSdk 34 / build-tools 34.0.0）、Gradle 8.7。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

签名（发布包）走 `keystore.properties`（该文件不入库）：

```properties
storeFile=your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## 模型文件

`app/src/main/assets/` 下已随仓库包含运行所需模型（共约 22 MB，均为 Apache-2.0）：

| 文件 | 说明 |
|---|---|
| `assets/ocr/det.onnx`、`rec.onnx`、`keys.txt` | PaddleOCR PP-OCRv4 中英文检测 / 识别模型（ONNX） |
| `assets/layout/layout_cdla.onnx` | 版面检测模型（10 类：text / title / figure / table / header / footer …），用于自动框题 |
| `assets/ocr/test.png`、`assets/sample_tiku.csv` | 自测与示例数据 |

如不需要版面模型（约 7 MB）可从 `.gitignore` 与 `assets/layout/` 移除，拍照搜题会退回纯 OCR 行框聚簇。

## 依赖与致谢

- [PaddleOCR / PP-OCRv4](https://github.com/PaddlePaddle/PaddleOCR) — OCR 模型（Apache-2.0）
- [RapidLayout](https://github.com/RapidAI/RapidLayout) — 版面模型与解码算法参考（Apache-2.0）
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — 端侧推理（MIT）
- [jxl](https://jxl.sourceforge.net/) — 读取老式 `.xls`（LGPL）
- 云端 AI 默认使用智谱 [GLM-4V-Flash](https://docs.bigmodel.cn/)（官方免费额度）

## 隐私

- 题库、做题记录全部存本机，不上传
- 只有**开启云端 AI 后**、且**本地检索失败时**才会把题目图片发到你配置的服务商；不开启则全程离线
- 不申请联网以外的敏感权限（无设备信息、不扫描已装应用）

## License

[MIT](LICENSE)
