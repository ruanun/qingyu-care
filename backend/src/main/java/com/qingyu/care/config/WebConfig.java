package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Web 配置 (CORS + 拦截器)
 *
 * <p>CORS 分两套（安全审查 H6，2026-09-03）：
 * <ul>
 *   <li>{@code /open/v1/**} 与 {@code /widget.js}：Widget 嵌在任意第三方站点，允许任意来源，
 *       但<b>不带凭据</b>——它靠 {@code X-App-Id} + Origin 白名单鉴权，不用 cookie；</li>
 *   <li>其余管理端接口：只允许 {@code qingyu.care.cors.allowed-origins} 里配置的前端来源。
 *       管理前端与后端同源（生产 nginx 反代 {@code /api/}、开发 Vite 代理），默认为空即可；
 *       只有把前端单独部署到别的域名时才需要配。</li>
 * </ul>
 * 此前 {@code /**} 一律 {@code allowedOriginPatterns("*")} + {@code allowCredentials(true)}，
 * 叠加 Sa-Token 默认写 cookie，任意网页都能带着受害者 cookie 读写管理 API。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final OpenApiAuthInterceptor openApiAuthInterceptor;

    /** 管理端允许的跨域来源（逗号分隔，精确匹配 scheme://host[:port]）；为空 = 不允许任何跨域。 */
    @Value("${qingyu.care.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 先注册更具体的路径：UrlBasedCorsConfigurationSource 按注册顺序取第一个匹配的配置
        registry.addMapping("/open/v1/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
        registry.addMapping("/widget.js")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .allowCredentials(false)
                .maxAge(3600);
        // 管理端：只放行配置的来源；列表为空时 Spring 对所有跨域请求回 403
        registry.addMapping("/**")
                .allowedOrigins(parseOrigins(corsAllowedOrigins).toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "satoken")
                .allowCredentials(true)
                .maxAge(3600);
    }

    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册租户拦截器
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/v3/api-docs/**", "/swagger-ui/**");
        // 注册开放 API 拦截器（仅 /open/v1/**）
        registry.addInterceptor(openApiAuthInterceptor)
                .addPathPatterns("/open/v1/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Widget 入口文件名固定、不带 hash，给较短缓存；嵌入片段用 ?v=<版本> 做 cache-bust。
        // 位置给目录（供解析器被调用），解析器固定返回文件名正确的 widget.js：
        // 直接把 location 当资源会因“文件当目录”而 Content-Type 退化为 octet-stream，
        // 返回明确的 ClassPathResource("static/widget.js") 才能识别为 JavaScript。
        registry.addResourceHandler("/widget.js")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    private final Resource widgetJs = new ClassPathResource("static/widget.js");

                    @Override
                    protected Resource getResource(String resourcePath, Resource location) {
                        return widgetJs.exists() && widgetJs.isReadable() ? widgetJs : null;
                    }
                });
    }
}
