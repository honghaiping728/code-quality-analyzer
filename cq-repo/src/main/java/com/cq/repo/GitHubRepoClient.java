package com.cq.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GitHub 仓库在线读取客户端（不克隆、不落盘）
 * <p>
 * 与 {@link GitService} 的"克隆到本地再读"不同，本客户端通过 GitHub 官方接口按需把源码
 * 拉到内存：{@code git/trees} 列文件清单、{@code compare} 取变更文件、{@code raw} 取文件正文，
 * 与"上传文件 / 粘贴代码"走同一条内存分析链路，磁盘上不留下任何痕迹。
 * <p>
 * 官方实现细节：
 * <ul>
 *     <li>{@code git@github.com:owner/repo.git} 形式的地址会被解析成 owner/repo 后走 https 接口
 *         （JVM 侧不需要任何 SSH 依赖）</li>
 *     <li>拉取正文统一用提交 SHA 拼 raw 地址，避免分支名含 {@code /} 时的路径歧义</li>
 *     <li>未认证访问 api.github.com 限流 60 次/小时（正文走 raw 域不计入该额度），
 *         配置 token 后提升为 5000 次/小时</li>
 * </ul>
 * <p>
 * 本类无可变状态，{@code HttpClient} 与 {@code ObjectMapper} 均线程安全，可并发调用。
 * 所有失败都抛 {@link GitHubApiException}，message 为面向用户的中文文案。
 */
public class GitHubRepoClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepoClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";
    /** owner / repo 允许的字符集 */
    private static final Pattern NAME_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final String USAGE_HINT =
            "当前仅支持 GitHub 仓库地址，形如 https://github.com/owner/repo.git 或 git@github.com:owner/repo.git";

    private final HttpClient http;
    private final String token;
    private final Duration requestTimeout;

    public GitHubRepoClient() {
        this("", 30);
    }

    /**
     * @param token GitHub API token，可为空（未认证限流 60 次/小时）
     * @param timeoutSeconds 单次请求超时（秒）
     */
    public GitHubRepoClient(String token, int timeoutSeconds) {
        this.token = token == null ? "" : token.trim();
        this.requestTimeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ==================== 地址解析 ====================

    /**
     * 仓库坐标
     * @param owner 用户名或组织名
     * @param repo 仓库名
     */
    public record RepoRef(String owner, String repo) {

        /** 规范化的 https 地址，用于落库与展示（git@ 等形式统一转换后存这一份） */
        public String canonicalUrl() {
            return "https://github.com/" + owner + "/" + repo;
        }
    }

    /**
     * 解析仓库地址为 owner/repo
     * <p>
     * 接受 {@code https://github.com/owner/repo(.git)}（含结尾斜杠、{@code /tree/...} 尾巴、
     * www 前缀、http 协议）与 {@code git@github.com:owner/repo(.git)}；其余一律拒绝并给出使用提示。
     * @param url 仓库地址
     * @return 仓库坐标
     * @throws IllegalArgumentException 地址为空、非 GitHub 或格式非法
     */
    public static RepoRef parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("仓库地址不能为空");
        }
        String text = url.trim();
        String tail;
        if (text.startsWith("git@github.com:")) {
            tail = text.substring("git@github.com:".length());
        } else {
            String lower = text.toLowerCase();
            boolean httpsForm = lower.startsWith("https://github.com/") || lower.startsWith("http://github.com/")
                    || lower.startsWith("https://www.github.com/") || lower.startsWith("http://www.github.com/");
            if (!httpsForm) {
                throw new IllegalArgumentException(USAGE_HINT);
            }
            int hostIndex = lower.indexOf("github.com/");
            tail = text.substring(hostIndex + "github.com/".length());
        }
        while (tail.endsWith("/")) {
            tail = tail.substring(0, tail.length() - 1);
        }
        if (tail.endsWith(".git")) {
            tail = tail.substring(0, tail.length() - 4);
        }
        String[] segments = tail.split("/");
        if (segments.length < 2
                || !NAME_SEGMENT.matcher(segments[0]).matches()
                || !NAME_SEGMENT.matcher(segments[1]).matches()) {
            throw new IllegalArgumentException("仓库地址应为 owner/repo 形式，" + USAGE_HINT);
        }
        return new RepoRef(segments[0], segments[1]);
    }

    // ==================== 在线读取 ====================

    /**
     * 仓库默认分支名
     * @param ref 仓库坐标
     * @return 默认分支名
     */
    public String defaultBranch(RepoRef ref) {
        JsonNode json = getJson("/repos/" + ref.owner() + "/" + ref.repo());
        return json.path("default_branch").asText("main");
    }

    /**
     * 把分支名 / 标签名 / 提交号统一解析为提交 SHA
     * @param ref 仓库坐标
     * @param refOrSha 分支、标签或提交号
     * @return 40 位提交 SHA
     */
    public String resolveCommit(RepoRef ref, String refOrSha) {
        JsonNode json = getJson("/repos/" + ref.owner() + "/" + ref.repo()
                + "/commits/" + encodeSegment(refOrSha));
        String sha = json.path("sha").asText("");
        if (sha.isEmpty()) {
            throw new GitHubApiException("无法解析提交：" + refOrSha);
        }
        return sha;
    }

    /**
     * 提交的父提交 SHA
     * @param ref 仓库坐标
     * @param sha 提交 SHA
     * @return 父提交 SHA；仓库只有一次提交时返回 null
     */
    public String parentCommit(RepoRef ref, String sha) {
        JsonNode json = getJson("/repos/" + ref.owner() + "/" + ref.repo()
                + "/commits?sha=" + encodeSegment(sha) + "&per_page=2");
        if (json.isArray() && json.size() >= 2) {
            String parent = json.get(1).path("sha").asText("");
            return parent.isEmpty() ? null : parent;
        }
        return null;
    }

    /**
     * 指定提交下全部 .java 文件路径
     * @param ref 仓库坐标
     * @param sha 提交 SHA
     * @return 仓库相对路径列表
     */
    public List<String> javaPathsAt(RepoRef ref, String sha) {
        JsonNode json = getJson("/repos/" + ref.owner() + "/" + ref.repo()
                + "/git/trees/" + encodeSegment(sha) + "?recursive=1");
        if (json.path("truncated").asBoolean(false)) {
            throw new GitHubApiException("仓库文件树过大，GitHub 仅返回了部分条目，暂不支持该规模的仓库");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode node : json.path("tree")) {
            if (!"blob".equals(node.path("type").asText())) {
                continue;
            }
            String path = node.path("path").asText("");
            if (path.endsWith(".java")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * 两次提交之间变更（新增 / 修改 / 重命名 / 复制）的 .java 文件路径
     * @param ref 仓库坐标
     * @param baseSha 基线提交 SHA
     * @param headSha 目标提交 SHA
     * @return 仓库相对路径列表（重命名的取新路径，删除的除外）
     */
    public List<String> changedJavaPaths(RepoRef ref, String baseSha, String headSha) {
        JsonNode json = getJson("/repos/" + ref.owner() + "/" + ref.repo()
                + "/compare/" + encodeSegment(baseSha) + "..." + encodeSegment(headSha));
        List<String> paths = new ArrayList<>();
        for (JsonNode file : json.path("files")) {
            if ("removed".equals(file.path("status").asText())) {
                continue;   // 被删除的文件没有可分析的正文
            }
            String path = file.path("filename").asText("");
            if (path.endsWith(".java")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * 拉取指定提交下的文件正文
     * @param ref 仓库坐标
     * @param sha 提交 SHA（用 SHA 而非分支名，避免分支名含 / 时的路径歧义）
     * @param path 仓库相对路径
     * @return 文件文本（UTF-8）
     */
    public String fetchFile(RepoRef ref, String sha, String path) {
        String url = RAW_BASE + "/" + ref.owner() + "/" + ref.repo() + "/" + sha + "/" + encodePath(path);
        HttpResponse<String> response = send(requestFor(url).GET().build());
        if (response.statusCode() != 200) {
            throw new GitHubApiException("文件拉取失败（HTTP " + response.statusCode() + "）：" + path);
        }
        return response.body();
    }

    // ==================== HTTP 内部 ====================

    private JsonNode getJson(String apiPath) {
        HttpResponse<String> response = send(requestFor(API_BASE + apiPath).GET().build());
        if (response.statusCode() != 200) {
            log.debug("GitHub API {} 返回 HTTP {}", apiPath, response.statusCode());
            throw describeError(response.statusCode(), response);
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new GitHubApiException("GitHub API 响应解析失败", e);
        }
    }

    private HttpRequest.Builder requestFor(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", "code-quality-analyzer")
                .header("Accept", "application/vnd.github+json");
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GitHubApiException("访问 GitHub 失败：网络不可达或超时（"
                    + requestTimeout.toSeconds() + "s），请稍后重试", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("访问 GitHub 被中断", e);
        }
    }

    /** 把 HTTP 状态码翻译成面向用户的中文文案（不暴露内部细节） */
    private static GitHubApiException describeError(int status, HttpResponse<String> response) {
        if (status == 404) {
            return new GitHubApiException("GitHub 未找到对应资源：仓库或提交不存在（也可能是私有仓库，当前仅支持公开仓库）");
        }
        if (status == 401) {
            return new GitHubApiException("GitHub 拒绝访问：该仓库需要凭证，当前仅支持公开仓库");
        }
        if (status == 403) {
            boolean rateLimited = response.headers().firstValue("X-RateLimit-Remaining")
                    .map("0"::equals)
                    .orElse(false);
            if (rateLimited) {
                return new GitHubApiException("GitHub API 未认证限流已用尽（每小时 60 次），请稍后重试，"
                        + "或配置 cq.repo.github-token 提升额度");
            }
            return new GitHubApiException("GitHub 拒绝了本次请求（HTTP 403），请稍后重试");
        }
        if (status == 409) {
            return new GitHubApiException("仓库为空：可能还没有任何提交");
        }
        if (status == 422) {
            return new GitHubApiException("GitHub 无法处理该请求：分支或提交号可能不存在");
        }
        if (status >= 500) {
            return new GitHubApiException("GitHub 服务暂时不可用（HTTP " + status + "），请稍后重试");
        }
        return new GitHubApiException("GitHub API 请求失败（HTTP " + status + "）");
    }

    /** 把分支名等编码为单个 URL 路径段（含 / 的分支名会被整体编码） */
    private static String encodeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分支或提交号不能为空");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 逐个路径段编码，保留 / 作为分隔符 */
    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }
}
