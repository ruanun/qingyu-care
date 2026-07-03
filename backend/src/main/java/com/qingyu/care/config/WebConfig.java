package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
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

/**
 * Web 配置 (CORS + 拦截器)
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final OpenApiAuthInterceptor openApiAuthInterceptor;
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "satoken")
                .allowCredentials(true)
                .maxAge(3600);
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
