package com.cq.web.dto;

/**
 * 粘贴代码审查的请求体
 */
public class SnippetRequest {

    private String fileName;   // 展示用文件名，可为空
    private String source;     // 源码内容

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
