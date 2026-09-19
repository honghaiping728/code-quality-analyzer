package com.cq.web;

import com.cq.parser.AstParserService;
import com.cq.repo.GitService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础能力装配
 * <p>
 * {@link GitService} 与 {@link AstParserService} 刻意标注在 cq-web 而非各自模块：
 * cq-repo / cq-parser 保持不依赖 Spring，便于单独测试与复用，由上层负责把它们
 * 纳入容器。这样这两个模块的类可以在任何普通 Java 程序里直接 new 出来用。
 */
@Configuration
public class CoreBeanConfiguration {

    @Bean
    public GitService gitService() {
        return new GitService();
    }

    @Bean
    public AstParserService astParserService() {
        return new AstParserService();
    }
}
