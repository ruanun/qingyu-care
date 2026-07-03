package com.qingyu.care.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 配置
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 登录校验：所有接口都需要登录
            SaRouter.match("/**")
                    // 排除不需要登录的接口
                    .notMatch(
                            "/v1/auth/login",
                            "/v1/auth/register",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/actuator/**",
                            "/open/v1/**",
                            "/widget.js"   // 公开嵌入的挂件脚本，外部 <script> 无登录态，必须放行
                    )
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/**");
    }
}
