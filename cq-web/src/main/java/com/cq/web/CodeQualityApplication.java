package com.cq.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeQualityApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeQualityApplication.class, args);
        System.out.println("=========================================");
        System.out.println("  智能代码质量分析与修复系统 启动成功！");
        System.out.println("=========================================");
    }
}
