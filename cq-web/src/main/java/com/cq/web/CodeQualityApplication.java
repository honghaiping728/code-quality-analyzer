package com.cq.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类
 * <p>
 * 扫描范围覆盖 com.cq 全包：各业务模块的服务都标注了 {@code @Service}，
 * 需要被统一装配到同一个容器中。
 */
@SpringBootApplication(scanBasePackages = "com.cq")
@MapperScan("com.cq.**.mapper")
public class CodeQualityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeQualityApplication.class, args);
        System.out.println("=========================================");
        System.out.println("  智能代码质量分析与修复系统 启动成功！");
        System.out.println("  控制台地址: http://localhost:8080/");
        System.out.println("=========================================");
    }
}
