package com.cq.common.lang;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 可审查的源码 / 配置文件语言
 * <p>
 * 本枚举是「文件 → 用哪个解析器 → 跑哪些规则」这条链路的起点：扫描链路据此决定
 * 收不收这个文件，规则引擎据此决定某条规则要不要在这个文件上执行。
 * <p>
 * 识别方式有两类：
 * <ul>
 *     <li><b>按扩展名</b>：绝大多数语言，如 {@code .yml} → YAML</li>
 *     <li><b>按文件名</b>：Dockerfile、Makefile 这类约定文件名没有扩展名，
 *         且允许带后缀（{@code Dockerfile.dev}、{@code Makefile.am}）</li>
 * </ul>
 * 两者都不匹配时返回 {@link #UNKNOWN}，调用方据此跳过该文件 —— 这样二进制文件、
 * 图片、构建产物天然不会被扫描，无需再做内容嗅探。
 * <p>
 * 放在 {@code cq-common} 是因为它是唯一没有外部依赖、且能被 repo / parser / rule /
 * scan / web 各模块共同引用的位置。
 */
public enum Language {

    /** Java：唯一有真实 AST 的语言，规则由 JavaParser 支撑 */
    JAVA("Java", "java"),

    YAML("YAML", "yml", "yaml"),

    /** 键值型配置：properties / ini / .env / cfg / conf 写法高度一致，规则可共用 */
    PROPERTIES("配置文件", "properties", "ini", "env", "cfg", "conf"),

    JSON("JSON", "json"),

    XML("XML", "xml", "xsd", "xsl", "xslt", "wsdl"),

    TOML("TOML", "toml"),

    SQL("SQL", "sql"),

    SHELL("Shell", "sh", "bash", "zsh", "ksh"),

    JAVASCRIPT("JavaScript", "js", "jsx", "mjs", "cjs"),

    TYPESCRIPT("TypeScript", "ts", "tsx", "mts", "cts"),

    VUE("Vue", "vue"),

    PYTHON("Python", "py", "pyw", "pyi"),

    GO("Go", "go"),

    CSS("CSS", "css", "scss", "sass", "less"),

    MARKDOWN("Markdown", "md", "markdown"),

    /**
     * 密钥与证书素材
     * <p>
     * 单列一类是为了让「私钥被提交进仓库」这条规则真的能看到文件：私有部署里
     * {@code id_rsa}、{@code server.pem} 这类文件极为常见，若识别不出来它们会在
     * 扫描入口就被跳过，安全事故反而查不到。
     */
    KEY_MATERIAL("密钥文件", "pem", "key", "p12", "pfx", "jks", "der", "ppk", "keystore"),

    /** 按文件名识别，无扩展名 */
    DOCKERFILE("Dockerfile"),

    /** 主要按文件名识别（Makefile / GNUmakefile / Makefile.am），辅以 .mk 扩展名 */
    MAKEFILE("Makefile", "mk"),

    /** 无法识别的类型，扫描链路会直接跳过 */
    UNKNOWN("未知");

    /** 扩展名 -> 语言；扩展名一律小写且不含点号 */
    private static final Map<String, Language> BY_EXTENSION = new HashMap<>();

    /** 完整文件名 -> 语言；仅用于没有扩展名的约定文件名 */
    private static final Map<String, Language> BY_FILENAME = new HashMap<>();

    /** 所有可扫描语言（即除 {@link #UNKNOWN} 外的全部），供「语言无关」的规则声明适用范围 */
    private static final Set<Language> SCANNABLE = Collections.unmodifiableSet(
            EnumSet.complementOf(EnumSet.of(UNKNOWN)));

    static {
        for (Language language : values()) {
            for (String extension : language.extensions) {
                BY_EXTENSION.put(extension, language);
            }
        }
        BY_FILENAME.put("dockerfile", DOCKERFILE);
        BY_FILENAME.put("containerfile", DOCKERFILE);
        BY_FILENAME.put("makefile", MAKEFILE);
        BY_FILENAME.put("gnumakefile", MAKEFILE);
        // OpenSSH 私钥默认没有扩展名，只能按文件名认
        BY_FILENAME.put("id_rsa", KEY_MATERIAL);
        BY_FILENAME.put("id_dsa", KEY_MATERIAL);
        BY_FILENAME.put("id_ecdsa", KEY_MATERIAL);
        BY_FILENAME.put("id_ed25519", KEY_MATERIAL);
    }

    private final String displayName;
    private final String[] extensions;

    Language(String displayName, String... extensions) {
        this.displayName = displayName;
        this.extensions = extensions;
    }

    /** 展示名（中文），用于前端与报告 */
    public String displayName() {
        return displayName;
    }

    /** 本语言的全部扩展名，无扩展名时为空数组 */
    public String[] extensions() {
        return extensions.clone();
    }

    /** 是否为可扫描语言（{@link #UNKNOWN} 表示不可扫描） */
    public boolean isScannable() {
        return this != UNKNOWN;
    }

    /**
     * 全部可扫描语言
     * <p>
     * 供「与语言无关」的规则声明适用范围：行尾空白、遗留的合并冲突标记这类问题，
     * 在 Java 与在 YAML 里同样成立，逐语言列举既啰嗦又容易漏。
     * @return 不含 {@link #UNKNOWN} 的不可变集合
     */
    public static Set<Language> scannable() {
        return SCANNABLE;
    }

    /**
     * 按文件路径识别语言
     * <p>
     * 路径可以带目录、可以是 Windows 风格的反斜杠分隔，也可以只是一个文件名。
     * 识别不出来一律返回 {@link #UNKNOWN} 而不抛异常 —— 扫描链路会遍历大量文件，
     * 「这个文件不认识」是常态而非异常。
     * @param path 文件路径或文件名，可为 null
     * @return 识别出的语言，无法识别时为 {@link #UNKNOWN}
     */
    public static Language of(String path) {
        if (path == null) {
            return UNKNOWN;
        }
        String normalized = path.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String fileName = (slash >= 0 ? normalized.substring(slash + 1) : normalized)
                .toLowerCase(Locale.ROOT);
        if (fileName.isEmpty()) {
            return UNKNOWN;
        }

        // 约定文件名优先：Dockerfile.dev 若先按扩展名走会落到 "dev" 而识别失败
        Language byName = BY_FILENAME.get(fileName);
        if (byName != null) {
            return byName;
        }
        if (fileName.startsWith("dockerfile.") || fileName.endsWith(".dockerfile")) {
            return DOCKERFILE;
        }
        if (fileName.startsWith("makefile.")) {
            return MAKEFILE;
        }
        // .env.local / .env.production 这类带环境后缀的写法取不到 env 扩展名
        if (fileName.startsWith(".env")) {
            return PROPERTIES;
        }

        // 取最后一个点号之后的片段：foo.spec.js 要落到 js，而不是 spec.js。
        // 隐藏文件如 .env 的点号在第 0 位，substring(1) 恰好得到 env；
        // .gitignore 同样走到这里但 "gitignore" 不在扩展名表中，会正确落到 UNKNOWN
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return UNKNOWN;
        }
        return BY_EXTENSION.getOrDefault(fileName.substring(dot + 1), UNKNOWN);
    }

    /**
     * 按语言取展示名，未知语言回退到枚举名
     * @param language 语言，可为 null
     * @return 展示用文本
     */
    public static String displayNameOf(Language language) {
        return language == null ? UNKNOWN.displayName : language.displayName;
    }

    /** 语言 -> 展示名，供需要一次性输出全部语言的场景使用 */
    public static Map<Language, String> displayNames() {
        Map<Language, String> names = new EnumMap<>(Language.class);
        for (Language language : values()) {
            names.put(language, language.displayName);
        }
        return names;
    }
}
