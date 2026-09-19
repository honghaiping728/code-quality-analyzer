package com.cq.rule;

import com.cq.common.Issue;
import com.cq.common.Severity;
import com.cq.common.model.RuleConfigSet;
import com.cq.parser.ParsedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎
 * <p>
 * 单文件检查流程：忽略文件过滤 → 禁用规则过滤 → 逐条执行 → 忽略项过滤 → 命中数封顶 →
 * 去重 → 稳定排序。
 * <p>
 * 几条刻意的设计选择：
 * <ul>
 *     <li><b>单条规则异常不影响整体</b>：任何规则抛异常只记录日志并跳过，
 *         否则一条写得不好的规则会让整个扫描失败</li>
 *     <li><b>命中数封顶</b>：生成代码可能让某条规则在单文件内命中上万次，
 *         既撑爆数据库也没有阅读价值</li>
 *     <li><b>稳定排序</b>：按文件、行号、严重级、置信度排序，保证同一份代码
 *         多次扫描输出顺序一致，报告才能做跨版本 diff</li>
 * </ul>
 * 本类无状态，可安全并发调用；并发粒度建议按文件而非按规则。
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<Rule> rules;

    /** 使用内置的默认规则集 */
    public RuleEngine() {
        this(DefaultRules.all());
    }

    /**
     * 指定规则集
     * @param rules 规则列表
     */
    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** 引擎注册的全部规则 */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * 使用默认配置检查单个文件
     * @param file 解析结果
     * @return 问题列表
     */
    public List<Issue> analyze(ParsedFile file) {
        return analyze(file, new RuleConfigSet());
    }

    /**
     * 检查单个文件
     * @param file 解析结果
     * @param config 规则配置
     * @return 问题列表，按文件、行号、严重级、置信度稳定排序
     */
    public List<Issue> analyze(ParsedFile file, RuleConfigSet config) {
        RuleConfigSet effective = config == null ? new RuleConfigSet() : config;
        if (isIgnoredFile(file.getFilePath(), effective)) {
            return List.of();
        }
        RuleContext ctx = new RuleContext(file, effective);
        int cap = effective.getThresholds().getMaxFindingsPerRulePerFile();
        List<Issue> collected = new ArrayList<>();

        for (Rule rule : rules) {
            if (!effective.isEnabled(rule.id())) {
                continue;   // 禁用的规则直接不执行，这是最省的一次过滤
            }
            List<Issue> found;
            try {
                found = rule.check(ctx);
            } catch (RuntimeException e) {
                log.warn("规则 {} 执行失败，已跳过: {}", rule.id(), e.toString());
                continue;
            }
            if (found == null || found.isEmpty()) {
                continue;
            }
            int accepted = 0;
            for (Issue issue : found) {
                if (accepted >= cap) {
                    log.debug("规则 {} 在文件 {} 命中数达到上限 {}", rule.id(), file.getFilePath(), cap);
                    break;
                }
                if (ctx.isIgnored(rule.id(), issue.getLine())) {
                    continue;   // 被忽略列表命中，直接不产出
                }
                collected.add(issue);
                accepted++;
            }
        }
        return dedupeAndSort(collected);
    }

    /**
     * 去重并稳定排序
     * <p>
     * 同一条规则在同一文件的同一行通常只应报告一次：规则的多个 AST 匹配点可能落在
     * 同一行（例如嵌套循环里同一处调用被内外两层循环各匹配一次）。
     */
    private static List<Issue> dedupeAndSort(List<Issue> issues) {
        Map<String, Issue> unique = new LinkedHashMap<>();
        for (Issue issue : issues) {
            String key = issue.getFilePath() + "|" + issue.getLine() + "|" + issue.getRuleId()
                    + "|" + issue.getMessage();
            unique.putIfAbsent(key, issue);
        }
        List<Issue> result = new ArrayList<>(unique.values());
        result.sort(Comparator
                .comparing(Issue::getFilePath, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Issue::getLine)
                .thenComparing(issue -> severityWeight(issue.getSeverity()), Comparator.reverseOrder())
                .thenComparing(Issue::getConfidence, Comparator.reverseOrder()));
        return result;
    }

    private static int severityWeight(String severity) {
        Severity parsed = Severity.parse(severity);
        return parsed == null ? 0 : parsed.weight();
    }

    /** 文件路径是否命中配置的忽略 glob */
    private static boolean isIgnoredFile(String filePath, RuleConfigSet config) {
        if (filePath == null || config.getThresholds().getIgnoredFilePatterns().isEmpty()) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        for (String pattern : config.getThresholds().getIgnoredFilePatterns()) {
            if (normalized.matches(RuleContext.globToRegex(pattern.replace('\\', '/')))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按规则 ID 取规则
     * @param ruleId 规则 ID
     * @return 规则，不存在时为空
     */
    public java.util.Optional<Rule> findById(String ruleId) {
        return rules.stream().filter(rule -> rule.id().equals(ruleId)).findFirst();
    }
}
