package com.cq.scan;

/**
 * 待分析的源码单元
 * <p>
 * 把「从哪里来的代码」抽象成「展示路径 + 源码内容」两个字段，扫描链路就不再关心
 * 输入是服务器目录、浏览器上传的文件，还是直接粘贴的一段代码 —— 三者走完全相同的
 * 解析、规则检查与落库流程。
 * @param displayPath 展示与定位用的路径，会写入问题的 file_path 字段
 * @param content 源码内容
 */
public record SourceUnit(String displayPath, String content) {

    /** 空内容判定，用于跳过空白粘贴 */
    public boolean isBlank() {
        return content == null || content.isBlank();
    }
}
