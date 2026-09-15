package com.cq.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    @GetMapping("/test")
    public String test() {
        return "智能代码质量分析系统 启动成功！";
    }
}
