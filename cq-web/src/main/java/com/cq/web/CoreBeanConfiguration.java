package com.cq.web;

import com.cq.parser.AstParserService;
import com.cq.repo.GitHubRepoClient;
import com.cq.repo.GitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础能力装配
 * <p>
 * {@link GitService}、{@link GitHubRepoClient} 与 {@link AstParserService} 刻意标注在
 * cq-web 而非各自模块：cq-repo / cq-parser 保持不依赖 Spring，便于单独测试与复用，
 * 由上层负责把它们纳入容器。这样这两个模块的类可以在任何普通 Java 程序里直接 new 出来用。
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

    /**
     * GitHub 在线读取客户端
     * @param token API token，可为空（未认证限流 60 次/小时，配置后 5000 次/小时）
     * @param timeoutSeconds 单次请求超时（秒）
     */
    @Bean
    public GitHubRepoClient gitHubRepoClient(@Value("${cq.repo.github-token:}") String token,
                                             @Value("${cq.repo.api-timeout-seconds:30}") int timeoutSeconds) {
        return new GitHubRepoClient(token, timeoutSeconds);
    }
}
