package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 有写端点的控制器必须明确选定权限方式：标 {@link AdminOnlyWrites}，或在下方登记「手动校验」并写明理由。
 *
 * <p>2026-09-25 安全扫描：10 个应用级配置控制器、50 多个写端点对坐席完全放开，坐席能改知识库、Prompt、
 * 模型、工具与 MCP。漏写 {@code requireTenantAdmin()} 不报错、不打日志，这条测试把「忘了想权限」变成红灯。
 * 同 {@code AsyncExecutorRoutingTest} 的扫描式守护。
 *
 * <p>⚠️ 登记进白名单前先确认那些没校验的写端点确实该对坐席开放，别把漏洞写进白名单。
 *
 * @author ruan
 */
class WriteEndpointRoleGuardTest {

    /** 手动校验的控制器 → 为什么不用 @AdminOnlyWrites */
    private static final Map<String, String> MANUAL = Map.ofEntries(
            Map.entry("AuthController", "登录/注册/登出本就面向未登录或任意已登录用户"),
            Map.entry("ChatController", "会话操作（测试对话、结束、标签、摘要、反馈、附件）本就给坐席；需坐席身份的接起/回复/交还/已读在方法内 requireAgent"),
            Map.entry("AgentStreamController", "坐席工作台的实时通道，方法内 requireAgent"),
            Map.entry("AnalyticsController", "方法内按角色校验"),
            Map.entry("ApplicationController", "读端点全体成员可用（顶部选应用），写端点逐个 requireTenantAdmin，密钥经 ApplicationView 脱敏"),
            Map.entry("UserController", "改他人须管理员、改自己放行（安全基线第 3 条），无法按 HTTP 方法一刀切"),
            Map.entry("TenantController", "平台级，方法内 checkPlatformAdmin"),
            Map.entry("TenantConfigController", "方法内 requireTenantAdmin"),
            Map.entry("PlatformController", "平台级，方法内 requirePlatformAdmin"),
            Map.entry("PlatformConfigController", "平台级，方法内 requirePlatformAdmin"),
            Map.entry("PlatformPackageController", "平台级，方法内 requirePlatformAdmin"),
            Map.entry("ToolPackageController", "方法内 requireTenantAdmin")
    );

    /** controller.open 包走开放 API 鉴权（appKey + 密钥 / Origin 白名单），不是登录态角色体系 */
    private static final String OPEN_API_PACKAGE = "com.qingyu.care.controller.open";

    @Test
    void 有写端点的控制器必须标AdminOnlyWrites或登记手动校验() throws Exception {
        List<String> unguarded = new ArrayList<>();
        for (Class<?> c : controllers()) {
            if (c.getPackageName().equals(OPEN_API_PACKAGE) || !hasWriteEndpoint(c)) continue;
            if (!c.isAnnotationPresent(AdminOnlyWrites.class) && !MANUAL.containsKey(c.getSimpleName())) {
                unguarded.add(c.getSimpleName());
            }
        }
        assertThat(unguarded)
                .as("这些控制器有写端点但没有选定权限方式：标 @AdminOnlyWrites，或确认后登记进 MANUAL 并写明理由")
                .isEmpty();
    }

    @Test
    void 白名单不得与注解重复_也不得登记不存在的类() throws Exception {
        List<String> names = new ArrayList<>();
        for (Class<?> c : controllers()) {
            names.add(c.getSimpleName());
            assertThat(c.isAnnotationPresent(AdminOnlyWrites.class) && MANUAL.containsKey(c.getSimpleName()))
                    .as(c.getSimpleName() + " 既标了注解又登记了手动校验，二选一").isFalse();
        }
        assertThat(names).as("白名单里有已不存在的控制器，删掉它").containsAll(MANUAL.keySet());
    }

    private static boolean hasWriteEndpoint(Class<?> c) {
        return Arrays.stream(c.getDeclaredMethods()).anyMatch(WriteEndpointRoleGuardTest::isWrite);
    }

    private static boolean isWrite(Method m) {
        return m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(PatchMapping.class);
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.qingyu.care.controller")) {
            out.add(Class.forName(bd.getBeanClassName()));
        }
        return out;
    }
}
