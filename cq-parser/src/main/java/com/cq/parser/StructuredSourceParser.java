package com.cq.parser;

import com.cq.common.ast.ParseProblems;
import com.cq.common.lang.Language;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXParseException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Set;

/**
 * 结构化配置文件解析器（JSON / YAML / XML）
 * <p>
 * 不产出 AST，只回答一个问题：**这个配置文件本身是不是合法的**。语法写错的
 * {@code application.yml} 或 {@code pom.xml} 在运行时才炸，属于最值得在评审阶段
 * 拦下的一类问题，而它恰好不需要任何语义分析就能判定。
 * <p>
 * 解析错误统一格式化为 {@code 第 N 行: <原因>} 写进
 * {@link com.cq.common.ast.AstSummary#getParseErrors()}：扫描链路据此把文件标记为
 * 解析失败，{@code BUG.INVALID_STRUCTURED_FILE} 规则再据此产出一条带行号的问题。
 * <p>
 * <b>安全约束（本工具自己不能成为漏洞）</b>：输入来自用户仓库，属于不可信内容。
 * <ul>
 *     <li>YAML 必须走 {@link SafeConstructor} —— 裸 {@code new Yaml()} 会按标签实例化
 *         任意类，等于把反序列化漏洞装进扫描器</li>
 *     <li>限制别名数量与嵌套深度，挡住 YAML 炸弹</li>
 *     <li>XML 关闭外部实体与外部 DTD 加载，并禁用实体展开，挡住 XXE 与实体膨胀</li>
 * </ul>
 */
public class StructuredSourceParser implements SourceParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredSourceParser.class);

    /**
     * 超过该字符数就**跳过**校验
     * <p>
     * 关键是跳过而非报错：{@code package-lock.json} 有数 MB，语法完全合法，
     * 把它报成「文件损坏」是本规则最可能翻车的地方。体积大的文件多半是数据文件
     * 而非配置，不校验远好过误报。阈值取 2 MB，与上传接口的单文件上限一致，
     * 保证「用户主动上传的文件一定被校验过」。
     */
    static final int MAX_VALIDATED_CHARS = 2 * 1024 * 1024;

    /** YAML 别名引用数量上限，挡「十亿笑声」式别名爆炸；与 SnakeYAML 默认值一致 */
    private static final int MAX_YAML_ALIASES = 50;

    /** YAML 嵌套深度上限 */
    private static final int MAX_YAML_DEPTH = 100;

    /** Jackson 的 ObjectMapper 线程安全，可作为共享实例 */
    private final ObjectMapper jsonMapper;

    public StructuredSourceParser() {
        // 重复键与「JSON 后面还跟着垃圾」都是实打实的配置错误，默认配置会放过它们
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.jsonMapper = new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public Set<Language> languages() {
        return Set.of(Language.JSON, Language.YAML, Language.XML);
    }

    @Override
    public ParsedFile parse(Language language, String source, String displayPath) {
        String text = source == null ? "" : source;
        ParsedFile parsed = new ParsedFile();
        parsed.setLanguage(language);
        parsed.setFilePath(displayPath);
        parsed.setSource(text);
        parsed.getSummary().setFilePath(displayPath);

        if (text.isBlank() || text.length() > MAX_VALIDATED_CHARS) {
            log.debug("跳过良构校验（空白或超出体积上限）: {}", displayPath);
            return parsed;
        }
        String error = switch (language) {
            case JSON -> validateJson(text);
            case YAML -> validateYaml(text);
            case XML -> validateXml(text);
            default -> null;
        };
        if (error != null) {
            parsed.getSummary().getParseErrors().add(error);
        }
        return parsed;
    }

    // ==================== JSON ====================

    private String validateJson(String text) {
        try {
            jsonMapper.readTree(text);
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            int line = e.getLocation() == null ? 1 : e.getLocation().getLineNr();
            return format(line, e.getOriginalMessage());
        } catch (RuntimeException e) {
            // 解析器内部异常（极少见）不应让整个扫描失败，降级为一条解析问题
            log.debug("JSON 校验异常", e);
            return format(1, e.getMessage());
        }
    }

    // ==================== YAML ====================

    private String validateYaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        options.setNestingDepthLimit(MAX_YAML_DEPTH);
        options.setCodePointLimit(MAX_VALIDATED_CHARS);
        options.setAllowDuplicateKeys(false);   // 重复键是实打实的配置错误，且检查是免费的
        // SafeConstructor：只构造标准类型，不会按 !!com.foo.Bar 标签实例化任意类
        // Yaml 实例非线程安全，而扫描是多线程按文件并发的，因此每次调用新建
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            // loadAll 而非 load：多文档 YAML（--- 分隔）是合法写法，load 会直接报错
            for (Object ignored : yaml.loadAll(text)) {
                // 只求遍历完不抛异常，不关心内容
            }
            return null;
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() == null ? 1 : e.getProblemMark().getLine() + 1;   // 标记是 0 起
            String problem = e.getProblem() == null ? e.getMessage() : e.getProblem();
            return format(line, problem);
        } catch (RuntimeException e) {
            log.debug("YAML 校验异常", e);
            return format(1, e.getMessage());
        }
    }

    // ==================== XML ====================

    private String validateXml(String text) {
        try {
            // 工厂与 builder 都非线程安全，且本方法可能被并发调用，这里每次新建。
            // XML 在校验总量里占比很低，这点开销换来的是不必给解析器加锁
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 加固调用刻意就近写在这里，而不是收进另一个方法：
            // 一来安全配置放在创建工厂的地方最显眼，二来本系统自己的 SEC.XXE 规则
            // 按「同一个方法内是否出现加固调用」判断，收进别处会让自检产生一条误报
            setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            // 双保险：即便某个实现忽略了上面的开关，实体解析也被导向空文档，
            // 不会真的去读本地文件或发网络请求
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new StringReader("")));
            builder.parse(new org.xml.sax.InputSource(new StringReader(text)));
            return null;
        } catch (SAXParseException e) {
            return format(e.getLineNumber(), e.getMessage());
        } catch (Exception e) {
            log.debug("XML 校验异常", e);
            return format(1, e.getMessage());
        }
    }

    /**
     * 逐项设置 XML 特性
     * <p>
     * 每一项都单独 try/catch：不同 XML 实现支持的特性集不一致，某一项不支持时应当
     * 退化为「该项未设置」而不是让整个校验失败。
     * <p>
     * <b>刻意不设置 {@code disallow-doctype-decl}</b>：MyBatis 映射文件、{@code web.xml}
     * 这类再正常不过的配置都带 DOCTYPE，一律禁掉会把它们全部误报成「文件损坏」。
     * 挡 SSRF / XXE 只需要「不加载外部 DTD、不解析外部实体」，不需要连 DOCTYPE 声明本身都拒绝。
     */
    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            log.debug("XML 特性不受支持，已跳过: {}", feature);
        }
    }

    private static void setAttribute(DocumentBuilderFactory factory, String name, String value) {
        try {
            factory.setAttribute(name, value);
        } catch (Exception e) {
            log.debug("XML 属性不受支持，已跳过: {}", name);
        }
    }

    // ==================== 工具 ====================

    /** 统一的解析问题文本，格式定义见 {@link ParseProblems} */
    static String format(int line, String message) {
        return ParseProblems.format(line, message);
    }
}
