package com.qingyu.care.config;

import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 配置
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * Sa-Token 1.42+ 改用 ThreadLocal 上下文模型，由 SaTokenContextFilterForJakartaServlet 初始化，
     * 但默认只注册 REQUEST 分发——SSE 流式接口的 ASYNC 二次分发会因无上下文抛 SaTokenContextException。
     * 这里显式包装同一个过滤器 bean，把分发类型扩展到 ASYNC/FORWARD/ERROR（Boot 检测到已有
     * FilterRegistrationBean 后会跳过原 bean 的默认注册，不会重复过滤）。
     */
    @Bean
    public FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> saTokenContextFilterRegistration(
            SaTokenContextFilterForJakartaServlet filter) {
        FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC,
                DispatcherType.FORWARD, DispatcherType.ERROR);
        registration.setOrder(-104); // 与过滤器自身 @Order(-104) 保持一致
        return registration;
    }


    /**
     * 把 Sa-Token 的日志换成脱敏实现（安全审查 L2）。内置的 {@code SaLogForConsole} 会把完整
     * 会话凭证打进 INFO 并直接 {@code System.out}；{@link TokenMaskingSaLog} 脱掉凭证后转交 SLF4J。
     *
     * <p>用 {@code @PostConstruct} 显式设置而非依赖自动装配：这条链路一旦静默失效，症状是
     * token 又开始进日志，而没有任何报错——显式调用至少能在启动日志里看到下面这行确认。
     */
    @jakarta.annotation.PostConstruct
    public void installMaskingLog() {
        cn.dev33.satoken.SaManager.setLog(new TokenMaskingSaLog());
    }

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
