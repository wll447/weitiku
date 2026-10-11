// 照原版 IOcrIsolateService：OCR 模型跑在 :ocr 独立进程，主进程通过 AIDL 调用，
// 避免 OCR 大模型把主进程拖崩/OOM 被杀（原版日志里的 ":ocr服务已连接"）。
package com.weizh.tiku.ocr;

interface IOcrIsolate {
    /** 预热：加载 ONNX 模型 */
    void warmUp();

    /** 模型是否已就绪 */
    boolean isReady();

    /**
     * 识别一张图片文件。返回每个文本行：
     *   text \t left,top,right,bottom
     * 行之间用 \n 分隔；返回空串表示没识别到内容。
     */
    String recognizeFile(String path);

    /**
     * 只做文字**检测**（不识别），返回每行框：left,top,right,bottom 每行一条。
     * 实时取景框题要每 ~700ms 跑一次，识别那步太慢，只有检测跟得上。
     */
    String detectFile(String path);

    /**
     * 版面区域检测。返回每行：left,top,right,bottom,score,classId。
     * 坐标位于输入图片坐标系；模型只给版面区域，不直接给完整题目。
     */
    String detectLayoutFile(String path);
}
