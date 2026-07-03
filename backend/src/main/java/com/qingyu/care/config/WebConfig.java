package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        // Widget 入口文件名固定、不带 hash，给较短缓存；嵌入片段用 ?v=<版本> 做 cache-bust
        registry.addResourceHandler("/widget.js")
                .addResourceLocations("classpath:/static/widget.js")
                .setCacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)));
    }
}
