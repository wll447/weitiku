# layout_cdla 接入基准与回归记录

## 目标

验证 WeiTiku 的 `layout_cdla.onnx` 接入是否能稳定完成：

1. 版面区域检测；
2. 题干与选项完整合并；
3. 多题纵向切分；
4. 失败时回退 OCR 行框；
5. 不向云端上传图片；
6. 不明显拖慢实时取景。

最终与小包做 A/B 时，必须使用同一批图片和同一题库，记录检测准确率、漏框率、误框率、OCR 端到端耗时 P50/P95 和本地命中率。

## 参考实现

RapidLayout PP handler 参考版本：本机 `rapid-layout 1.2.1`。

模型：`app/src/main/assets/layout/layout_cdla.onnx`

官方参考配置：

- input: `[1,3,800,608]`
- direct resize，不是 letterbox
- BGR → CHW
- `(x / 255 - mean) / std`
- confidence: `0.4`
- IoU: `0.5`
- strides: `8,16,32,64`
- per-level Top-K: `1000`
- per-class hard NMS Top-K: `100`

## 已验证测试

### 单题测试图

输入：`app/src/main/assets/ocr/test.png`，1000×420。

Python RapidLayout 参考输出：

```text
2 个 title 区域
```

Android MuMu x86_64 / Android API 35 输出：

```text
layout load ≈ 167–208 ms
首次推理 ≈ 231–266 ms
热推理 ≈ 119–160 ms
regions=2
#1 title score≈0.589 box≈45,159,403,203
#2 title score≈0.521 box≈48,51,472,103
```

Android OCR 返回 5 行后，题目合并框约为：

```text
normalized = 0.04603,0.12381,0.46897,0.73810
```

换算到原图约为：

```text
x=46..469, y=52..310
```

该框覆盖题干和 A/B/C/D 选项。

### 真实打印题目摄像头测试

在 MuMu 模拟器（x86_64 / Android API 35）摄像头前放置真实打印题目，实时取景画面中：

- 题目纸张被绿色框覆盖；
- 框包含连续题干和选项区域，而不是只框单个文字行；
- 背景中的风扇/衣帽架未被保留为最终题目框；
- layout 失败回退路径仍保留。

这是人工视觉验收，尚未形成像素级真值标注。

### 合成多题测试图

测试图由两道纵向题、页眉和间隔组成，仅用于回归算法，不作为真实准确率样本。

Android 输出：

```text
layout regions=7
question crop lines=10
question groups=2
```

两组题目均被切分出来，且 layout 区域与 OCR 行框共同参与合并。

## Debug 自检命令

```bash
adb shell am start -n com.weizh.tiku/.MainActivity --ez layouttest true
adb logcat -d -s WeiTikuSelfTest:V
```

自定义测试图片可通过 debug 包的 `layoutpath` extra 传入应用私有文件路径：

```bash
adb shell am start -n com.weizh.tiku/.MainActivity \
  --ez layouttest true \
  --es layoutpath /data/data/com.weizh.tiku/files/layout_multi_test.png
```

## 当前结论

- 模型输出解码与 RapidLayout 参考结果一致；
- AIDL 隔离进程链路可用；
- 单题完整题框合并已验证；
- 合成多题切分已验证；
- layout 失败仍回退 OCR 行框；
- 真实打印题目已完成摄像头人工验收，实时框覆盖了主要题目纸张区域；
- 目前没有小包同图的可重复计时/准确率数据，因此只能确认功能链路可用，不能量化宣称已经超过小包；
- 若要做严格 A/B，下一步需要双栏、表格、页眉干扰图片和原版小包同图结果。
