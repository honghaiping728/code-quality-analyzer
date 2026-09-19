package com.cq.web;

import com.cq.agent.CodeAgent;
import com.cq.common.Result;
import com.cq.rule.Rule;
import com.cq.rule.RuleEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统状态接口
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final DataSource dataSource;
    private final CodeAgent codeAgent;
    private final RuleEngine ruleEngine = new RuleEngine();

    public SystemController(DataSource dataSource, CodeAgent codeAgent) {
        this.dataSource = dataSource;
        this.codeAgent = codeAgent;
    }

    /** 存活探针 */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("ok");
    }

    /**
     * 运行状态：数据库连通性、大模型可用性、规则总数
     * <p>
     * 前端首页据此提示「当前为离线启发式模式」，避免使用者误以为大模型已接入。
     * @return 状态信息
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("database", databaseStatus());
        status.put("llmAvailable", codeAgent.available());
        status.put("llmClient", codeAgent.clientName());
        status.put("ruleCount", ruleEngine.rules().size());
        status.put("rulesByCategory", countByCategory());
        return Result.success(status);
    }

    /** 数据库连通性 */
    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? "connected" : "unreachable";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** 各维度的规则数量 */
    private Map<String, Long> countByCategory() {
        List<Rule> rules = ruleEngine.rules();
        return rules.stream().collect(Collectors.groupingBy(
                rule -> rule.type().name(), LinkedHashMap::new, Collectors.counting()));
    }
}
