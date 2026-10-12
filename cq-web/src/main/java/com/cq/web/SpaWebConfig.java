package com.cq.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.io.IOException;
import java.util.Enumeration;
import java.util.List;

/**
 * 单页应用（history 模式）的路由回退
 * <p>
 * 控制台是 history 模式路由：直接访问或刷新 {@code /issues} 这类路径时服务端没有对应资源，
 * 需要把请求交给 index.html 由前端路由接管。这里在静态资源解析链上挂一个解析器：
 * <ul>
 *   <li>{@code /api/**} 与 {@code /test} 不接管 —— 未知接口应保持 404，而不是回一段 HTML 让调用方拿到 200；</li>
 *   <li>带扩展名的路径（{@code /assets/xxx.js}）不接管 —— 缺失的静态资源应当 404，
 *       回退成 HTML 会表现为「页面白屏但状态码 200」，极难排查；</li>
 *   <li>非 HTML 导航请求（{@code fetch} 打错路径）也不接管，理由同上；</li>
 *   <li>其余（{@code /issues}、{@code /issues/12}、{@code /llm} …）返回 index.html。</li>
 * </ul>
 * 之所以走资源解析器而不是 {@code addViewControllers}：后者注册进 RequestMappingHandlerMapping
 * （order 0，先于静态资源映射被查询），用 {@code /**} 会把静态资源一起 forward 成 HTML；
 * 而走解析器时 index.html 仍由 ResourceHttpRequestHandler 正常返回，Content-Type、304 都统一处理。
 * <p>
 * 注意：注册 {@code /**} 会整体替换 Spring Boot 默认的静态资源处理器，因此这里把 Boot 的默认
 * location 原样补回；同时也意味着 {@code spring.web.resources.*} 的配置项对本应用不再生效。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String INDEX = "/static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                // Spring Boot 的默认位置，原样补回（见类注释）
                .addResourceLocations(
                        "classpath:/META-INF/resources/",
                        "classpath:/resources/",
                        "classpath:/static/",
                        "classpath:/public/")
                // false = 不启用 CachingResourceResolver：开发时产物目录会被反复重写，
                // 缓存里指向已删除文件的条目会让「旧 hash 资源 404」变成 500
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {

                    @Override
                    @Nullable
                    protected Resource resolveResourceInternal(@Nullable HttpServletRequest request,
                                                               String requestPath,
                                                               List<? extends Resource> locations,
                                                               ResourceResolverChain chain) {
                        for (Resource location : locations) {
                            try {
                                Resource found = getResource(requestPath, location);
                                if (found != null) {
                                    return found;
                                }
                            } catch (IOException ignored) {
                                // 与父类一致：某个 location 出错就跳过
                            }
                        }
                        if (request != null && !isHtmlNavigation(request)) {
                            return null;
                        }
                        return shouldFallbackToIndex(requestPath) ? new ClassPathResource(INDEX) : null;
                    }
                });
    }

    /** 只有前端路由路径才回退到 index.html */
    static boolean shouldFallbackToIndex(String resourcePath) {
        if (resourcePath.isEmpty()) {
            return true;
        }
        if (resourcePath.equals("api") || resourcePath.startsWith("api/")) {
            return false;
        }
        if (resourcePath.equals("test") || resourcePath.startsWith("test/")) {
            return false;
        }
        String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }

    /** 是否为浏览器地址栏导航（而非脚本发起的 fetch/XHR） */
    private static boolean isHtmlNavigation(HttpServletRequest request) {
        Enumeration<String> accepts = request.getHeaders(HttpHeaders.ACCEPT);
        while (accepts != null && accepts.hasMoreElements()) {
            if (accepts.nextElement().contains(MediaType.TEXT_HTML_VALUE)) {
                return true;
            }
        }
        return false;
    }
}
