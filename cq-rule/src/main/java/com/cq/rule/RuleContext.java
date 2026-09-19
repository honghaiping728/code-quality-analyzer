package com.cq.rule;

import com.cq.common.Severity;
import com.cq.common.model.IgnoreEntry;
import com.cq.common.model.RuleConfig;
import com.cq.common.model.RuleConfigSet;
import com.cq.common.model.RuleThresholds;
import com.cq.parser.AstScopeUtils;
import com.cq.parser.ParsedFile;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则执行上下文
 * <p>
 * 承载单文件检查所需的全部信息，并做两件性能/正确性上的关键事：
 * <ol>
 *     <li><b>缓存 {@code findAll} 结果</b>：数十条规则各自遍历整棵树是 O(规则数 × 节点数)，
 *         按节点类型缓存后降到约十余次遍历</li>
 *     <li><b>按需构建类型索引</b>：只有真正用到类型的规则才会触发构建</li>
 * </ol>
 * 注意：{@link #findAll(Class)} 返回的是缓存内的共享列表，规则**不得修改**它。
 */
public class RuleContext {

    private final ParsedFile file;
    private final RuleConfigSet config;
    private final Map<Class<?>, List<?>> findCache = new HashMap<>();
    private LocalTypeIndex typeIndex;

    public RuleContext(ParsedFile file, RuleConfigSet config) {
        this.file = file;
        this.config = config == null ? new RuleConfigSet() : config;
    }

    /** 便捷构造：使用默认配置 */
    public RuleContext(ParsedFile file) {
        this(file, new RuleConfigSet());
    }

    // ==================== AST 访问 ====================

    /**
     * 按节点类型取全部匹配节点（结果带缓存，勿修改返回值）
     * @param nodeType 节点类型
     * @param <T> 节点泛型
     * @return 匹配节点列表，无 AST 时返回空列表
     */
    @SuppressWarnings("unchecked")
    public <T extends Node> List<T> findAll(Class<T> nodeType) {
        if (!file.hasUnit()) {
            return List.of();
        }
        // 注意：不能写成 computeIfAbsent(nodeType, type -> ... findAll(type))，
        // 缓存值的通配类型会让 findAll 的类型推断失败
        List<?> cached = findCache.get(nodeType);
        if (cached == null) {
            cached = new ArrayList<>(file.getUnit().findAll(nodeType));
            findCache.put(nodeType, cached);
        }
        return (List<T>) cached;
    }

    /** 原始编译单元，未解析成功时为 null */
    public CompilationUnit unit() {
        return file.getUnit();
    }

    /** 类型索引（懒加载） */
    public LocalTypeIndex types() {
        if (typeIndex == null) {
            typeIndex = LocalTypeIndex.build(file.getUnit());
        }
        return typeIndex;
    }

    // ==================== 源码访问 ====================

    /** 文件路径 */
    public String filePath() {
        return file.getFilePath();
    }

    /** 取指定行源码原文 */
    public String lineAt(int line) {
        return file.lineAt(line);
    }

    /** 解析结果 */
    public ParsedFile parsed() {
        return file;
    }

    /**
     * 计算指定行内容的 SHA-256
     * <p>
     * 用作问题的稳定标识：行号会随上方代码编辑整体位移，仅按行号抑制会导致
     * 抑制在第一次提交后就失效或误抑制，因此以「源码行内容」为准。
     * @param line 行号
     * @return 十六进制哈希，行内容为空时返回 null
     */
    public String lineHash(int line) {
        String text = lineAt(line);
        if (text == null || text.isBlank()) {
            return null;
        }
        return sha256(text.trim());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;   // JDK 必然支持 SHA-256，不会走到这里
        }
    }

    // ==================== 配置 ====================

    /** 阈值配置 */
    public RuleThresholds thresholds() {
        return config.getThresholds();
    }

    /**
     * 解析规则的生效严重级：配置覆盖优先，否则用规则默认值
     * @param rule 规则
     * @return 生效严重级
     */
    public Severity severityOf(Rule rule) {
        RuleConfig ruleConfig = config.getRules().get(rule.id());
        if (ruleConfig != null && ruleConfig.getSeverity() != null) {
            Severity override = Severity.parse(ruleConfig.getSeverity());
            if (override != null) {
                return override;
            }
        }
        return rule.severity();
    }

    /**
     * 解析规则的生效置信度：配置覆盖优先，否则用规则默认值
     * @param rule 规则
     * @return 生效置信度
     */
    public double confidenceOf(Rule rule) {
        RuleConfig ruleConfig = config.getRules().get(rule.id());
        if (ruleConfig != null && ruleConfig.getConfidence() != null) {
            return ruleConfig.getConfidence();
        }
        return rule.confidence();
    }

    /**
     * 判断某条命中是否被忽略列表命中
     * @param ruleId 规则 ID
     * @param line 行号
     * @return 是否应忽略
     */
    public boolean isIgnored(String ruleId, int line) {
        if (config.getIgnoreEntries().isEmpty()) {
            return false;
        }
        String currentHash = lineHash(line);
        for (IgnoreEntry entry : config.getIgnoreEntries()) {
            if (!entry.isEnabled() || entry.isExpired()) {
                continue;
            }
            if (entry.getRuleId() != null && !entry.getRuleId().equals(ruleId)) {
                continue;
            }
            if (!matchesPath(entry.getFilePattern())) {
                continue;
            }
            // lineHash 为 null 表示忽略整个文件；否则必须精确匹配同一行内容
            if (entry.getLineHash() == null || entry.getLineHash().equals(currentHash)) {
                return true;
            }
        }
        return false;
    }

    /** 忽略项的路径 glob 匹配 */
    private boolean matchesPath(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String path = filePath() == null ? "" : filePath().replace('\\', '/');
        return path.matches(globToRegex(pattern.replace('\\', '/')));
    }

    /**
     * 把路径 glob 转成正则
     * <p>
     * 逐字符扫描而非链式 {@code replace}：链式替换需要借占位符中转，
     * 一旦模式里出现真实空格等字符就会被污染。
     * <ul>
     *     <li>{@code **&#47;} 匹配零层或多层目录</li>
     *     <li>{@code **} 匹配任意字符</li>
     *     <li>{@code *} 只匹配单层内的任意字符</li>
     *     <li>{@code ?} 匹配单个非分隔符字符</li>
     * </ul>
     * @param glob glob 模式
     * @return 等价正则
     */
    public static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar && i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                    regex.append("(?:.*/)?");
                    i += 3;
                } else if (doubleStar) {
                    regex.append(".*");
                    i += 2;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (c == '.') {
                regex.append('\\').append('.');
                i++;
            } else if ("()[]{}+^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        return regex.toString();
    }

    /** 节点所在行号，取不到返回 -1 */
    public int lineOf(Node node) {
        return AstScopeUtils.lineOf(node);
    }
}
