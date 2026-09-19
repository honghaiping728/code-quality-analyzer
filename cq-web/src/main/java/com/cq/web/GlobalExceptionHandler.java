package com.cq.web;

import com.cq.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理
 * <p>
 * 让接口在任何异常下都返回统一结构的 JSON，而不是把堆栈直接吐给调用方 ——
 * 前者可以被前端稳定解析，后者只会暴露实现细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数不合法：属于调用方的问题，保留 400 语义 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /** 其余未预期异常：记录完整堆栈，但只向调用方返回简要信息 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("接口处理失败", e);
        return Result.error("服务处理失败: " + e.getMessage());
    }
}
