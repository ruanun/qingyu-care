package com.qingyu.care.controller;

import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.dto.AiSkillUpdateRequest;
import com.qingyu.care.dto.AiToolUpdateRequest;
import com.qingyu.care.dto.ApplicationUpdateRequest;
import com.qingyu.care.dto.ChatStrategyUpdateRequest;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.webhook.WebhookPayloadBuilder;
import com.qingyu.care.service.webhook.WebhookSender;
import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 局部更新不得把没传的字段打回类默认值。
 *
 * <p>这些测试守的是一类<b>不报错、只静默毁数据</b>的缺陷：实体字段写了初始化器（如
 * {@code private Integer timeoutMs = 5000;}），Jackson 反序列化用的无参构造会原样执行这些
 * 初始化器——请求体没给的字段拿到类默认值而非 {@code null}，{@code if (x != null)} 恒真。
 * 专用 DTO 不写任何字段初始化器，判空才成立。
 *
 * <p>⚠️ <b>根源是字段初始化器本身，与 {@code @Builder.Default} 注解无关</b>：
 * {@code @Builder.Default} 只影响 {@code XXX.builder().build()} 这条路径是否也应用该默认值；
 * 不加它时 Lombok 只是警告「初始化表达式被忽略」，builder 路径的默认值退化成
 * null/0/false，但无参构造（Jackson 反序列化走的正是这条）不受影响，照样应用初始化器。
 * 下面 {@code KnowledgeBaseRequest没有BuilderDefault时无参构造依然应用初始化器} 一条把这个
 * 结论钉成断言：{@code KnowledgeBaseRequest} 有 {@code @Builder} +
 * {@code private Boolean enabled = true;} 却<b>没有</b> {@code @Builder.Default}，
 * {@code new KnowledgeBaseRequest().getEnabled()} 依然是 {@code true}。所以本次修复的铁律是
 * 「专用 DTO 不写字段初始化器」，不是「不加 {@code @Builder.Default}」——后者只是顺带。
 *
 * <p><b>断言必须检查具体原值</b>（如 {@code isEqualTo(30000)}），不能只断言非 null——
 * 后者在字段被打回默认值时同样成立，是永真测试。
 *
 * <p>下列涉及控制器的测试优先直调真实控制器方法（{@code @InjectMocks}）而非在测试里复刻映射
 * 逻辑——复刻的两份代码会漂移，删掉控制器里的 {@code if (x != null)} 不会让复刻版测试变红。
 * {@code UserController} 的密码判空曾因「权限判断依赖登录态、mock 成本更高」而留在这里以复刻
 * 形态覆盖；全分支终审指出这条测试对生产代码零守护（把控制器里的判断改回只判
 * {@code != null} 不会转红），已改用 {@code mockStatic(StpUtil.class)} 直调真实控制器，
 * 挪到 {@link UserControllerTest}（一并补了提权守卫与 {@code enabled}/{@code role} 管理员
 * 门禁两条此前零覆盖的分支），本类不再重复。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PartialUpdateSemanticsTest {

    @Mock
    ChatToolService chatToolService;
    @InjectMocks
    AiSkillController aiSkillController;

    @Mock
    TenantRepository tenantRepository;
    @Mock
    AuthService authService;
    @InjectMocks
    TenantController tenantController;

    @Mock
    ModelRouterService modelRouterService;
    @InjectMocks
    AiModelController aiModelController;

    @Mock
    ApplicationRepository applicationRepository;
    @Mock
    WebhookSender webhookSender;
    @Mock
    WebhookPayloadBuilder webhookPayloadBuilder;
    @InjectMocks
    ApplicationController applicationController;

    @Mock
    ChatStrategyService chatStrategyService;
    @InjectMocks
    ChatStrategyController chatStrategyController;

    // PlatformController 的构造参数里，modelRouterService/tenantRepository/applicationRepository/
    // chatToolService 复用上面已声明的 @Mock 字段（同类型只有一份，Mockito 按类型注入不冲突）。
    @Mock
    UserRepository userRepository;
    @Mock
    ChatSessionRepository chatSessionRepository;
    @Mock
    HealthEndpoint healthEndpoint;
    @InjectMocks
    PlatformController platformController;

    @Test
    void DTO未赋值的字段一律为null_工具() {
        AiToolUpdateRequest req = new AiToolUpdateRequest();
        req.setEnabled(false);   // 只给一个字段，模拟前端点启用开关

        assertThat(req.getEnabled()).isFalse();
        assertThat(req.getHttpMethod()).isNull();
        assertThat(req.getTimeoutMs()).isNull();
        assertThat(req.getMaxResponseChars()).isNull();
        assertThat(req.getName()).isNull();
        assertThat(req.getAuthHeader()).isNull();
    }

    @Test
    void 实体作为请求体时未赋值字段拿到的是默认值而非null_这就是被修的缺陷() {
        // 反向对照：证明「用实体收请求体」为什么不行。这条测试固定住缺陷成因，
        // 以后有人想把 DTO 换回实体时会看到它。
        AiTool asRequestBody = new AiTool();

        assertThat(asRequestBody.getHttpMethod()).isEqualTo("GET");
        assertThat(asRequestBody.getTimeoutMs()).isEqualTo(5000);
        assertThat(asRequestBody.getMaxResponseChars()).isEqualTo(4000);
    }

    @Test
    void KnowledgeBaseRequest没有BuilderDefault时无参构造依然应用初始化器() {
        // 反向对照二：证明缺陷根源是字段初始化器本身，与 @Builder.Default 注解无关。
        // KnowledgeBaseRequest.enabled 只写了 `= true`，没有 @Builder.Default，
        // 但 Jackson 走的无参构造照样应用这个初始化器——把这条钉成断言，以后有人把它
        // 「修」成删掉 @Builder.Default 时，这条测试会告诉他这不解决问题。
        assertThat(new KnowledgeBaseRequest().getEnabled()).isTrue();
    }

    @Test
    void 技能局部更新不清空已绑定的工具() {
        AiSkill existing = AiSkill.builder().id(1L).name("退货助手").build();
        existing.setToolIds(new LinkedHashSet<>(Set.of(7L, 9L)));

        when(chatToolService.getSkill(1L)).thenReturn(existing);
        when(chatToolService.saveSkill(any(AiSkill.class))).thenReturn(existing);

        AiSkillUpdateRequest req = new AiSkillUpdateRequest();
        req.setEnabled(false);   // 前端点停用开关只发这一个字段

        aiSkillController.update(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        assertThat(existing.getToolIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(existing.getEnabled()).isFalse();
    }

    @Test
    void 租户局部更新不把套餐打回FREE() {
        Tenant existing = Tenant.builder().id(1L).name("云舟科技").build();
        existing.setPlan(TenantPlan.ENTERPRISE);
        existing.setStatus(TenantStatus.ACTIVE);

        when(authService.isPlatformAdmin()).thenReturn(true);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenReturn(existing);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.FROZEN);   // 前端冻结租户只发 status

        tenantController.update(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        assertThat(existing.getPlan()).isEqualTo(TenantPlan.ENTERPRISE);
        assertThat(existing.getStatus()).isEqualTo(TenantStatus.FROZEN);
    }

    @Test
    void 编辑AI模型配置时传空串apiKey不改动凭据() {
        AiModelConfig existing = AiModelConfig.builder()
                .id(1L).name("通义千问").provider("dashscope").modelId("qwen-max").build();
        existing.setApiKey("sk-OLD_KEY_PLACEHOLDER");

        when(modelRouterService.getById(1L)).thenReturn(existing);
        when(modelRouterService.saveModel(any(AiModelConfig.class))).thenReturn(existing);

        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setEnabled(false);   // 前端点停用开关只发这一个字段
        req.setApiKey("");       // 前端不回显凭据，编辑弹窗把它初始化为空串并照样提交

        aiModelController.updateModel(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        assertThat(existing.getApiKey()).isEqualTo("sk-OLD_KEY_PLACEHOLDER");
        assertThat(existing.getEnabled()).isFalse();
    }

    @Test
    void 应用局部更新不把主题色打回默认值() {
        Application existing = Application.builder().id(1L).name("云舟商城客服").build();
        existing.setThemeColor("#ff0000");
        existing.setEnabled(true);

        // 平台管理员短路 checkAppAccess 里的租户归属校验，无需搭 TenantContext/Tenant 关联
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(applicationRepository.save(any(Application.class))).thenReturn(existing);

        ApplicationUpdateRequest req = new ApplicationUpdateRequest();
        req.setEnabled(false);   // 前端点停用开关只发这一个字段

        applicationController.update(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        assertThat(existing.getThemeColor()).isEqualTo("#ff0000");
        assertThat(existing.getEnabled()).isFalse();
    }

    @Test
    void 问答策略局部更新不把相似度阈值打回默认值() {
        ChatStrategy existing = ChatStrategy.builder().id(1L).name("默认策略").scenario("default").build();
        existing.setSimilarityThreshold(0.68);
        existing.setEnabled(true);

        when(chatStrategyService.getById(1L)).thenReturn(existing);
        when(chatStrategyService.saveStrategy(any(ChatStrategy.class))).thenReturn(existing);

        ChatStrategyUpdateRequest req = new ChatStrategyUpdateRequest();
        req.setEnabled(false);   // 前端点停用开关只发这一个字段

        chatStrategyController.update(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        assertThat(existing.getSimilarityThreshold()).isEqualTo(0.68);
        assertThat(existing.getEnabled()).isFalse();
    }

    @Test
    void 问答策略局部更新不改动未传的混合检索参数_且传了的会真的生效() {
        ChatStrategy existing = ChatStrategy.builder().id(1L).name("默认策略").scenario("default").build();
        existing.setTopK(5);
        existing.setSimilarityThreshold(0.68);
        existing.setKeywordTopK(10);
        existing.setRrfK(60);
        existing.setHybridEnabled(true);

        when(chatStrategyService.getById(1L)).thenReturn(existing);
        when(chatStrategyService.saveStrategy(any(ChatStrategy.class))).thenReturn(existing);

        ChatStrategyUpdateRequest req = new ChatStrategyUpdateRequest();
        req.setHybridEnabled(false);   // 只传混合检索开关，模拟前端点关闭该开关

        chatStrategyController.update(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        // 方向一：没传的字段纹丝不动
        assertThat(existing.getTopK()).isEqualTo(5);
        assertThat(existing.getSimilarityThreshold()).isEqualTo(0.68);
        assertThat(existing.getKeywordTopK()).isEqualTo(10);
        assertThat(existing.getRrfK()).isEqualTo(60);
        assertThat(existing.getName()).isEqualTo("默认策略");
        // 方向二：传了的字段真的生效——只断言方向一是空转的，删掉赋值这条测试照样绿
        assertThat(existing.getHybridEnabled()).isFalse();
    }

    @Test
    void 问答策略局部更新_三个混合检索参数各自传入时都真的生效() {
        // 上一条测试只传 hybridEnabled，keywordTopK/rrfK 那两行赋值的「生效」分支从未被执行过
        // ——删掉其中任一行，全量单测原本无一转红。本条三个字段同时传新值，逐一断言到具体值，
        // 保证删掉三行赋值中的任意一行都会让对应那条断言精确转红。
        ChatStrategy existing = ChatStrategy.builder().id(1L).name("默认策略").scenario("default").build();
        existing.setHybridEnabled(true);
        existing.setKeywordTopK(10);
        existing.setRrfK(60);

        when(chatStrategyService.getById(1L)).thenReturn(existing);
        when(chatStrategyService.saveStrategy(any(ChatStrategy.class))).thenReturn(existing);

        ChatStrategyUpdateRequest req = new ChatStrategyUpdateRequest();
        req.setHybridEnabled(false);
        req.setKeywordTopK(20);
        req.setRrfK(30);

        chatStrategyController.update(1L, req);   // 直调真实控制器，而非复刻映射逻辑

        assertThat(existing.getHybridEnabled()).isFalse();
        assertThat(existing.getKeywordTopK()).isEqualTo(20);
        assertThat(existing.getRrfK()).isEqualTo(30);
    }

    @Test
    void 平台共享模型_传空白apiKey不覆盖既有凭据() {
        AiModelConfig existing = AiModelConfig.builder()
                .id(1L).name("共享模型").provider("dashscope").modelId("qwen-max")
                .scope(OwnerScope.PLATFORM).build();
        existing.setApiKey("sk-platform-real-key");

        when(modelRouterService.getById(1L)).thenReturn(existing);
        when(modelRouterService.saveSharedModel(any(AiModelConfig.class))).thenReturn(existing);

        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setName("改个名字");
        req.setApiKey("   ");   // 前端不回显凭据，编辑弹窗初始化为空串；粘贴还可能带来全空格

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requirePlatformAdmin).thenAnswer(inv -> null);

            platformController.updateSharedModel(1L, req);   // 直调真实控制器，而非复刻映射逻辑
        }

        assertThat(existing.getApiKey())
                .as("空白 apiKey 必须被当作「不修改」，否则平台共享模型的凭据被抹掉，"
                        + "所有绑定它的租户静默失效——第三方返 401，模型拿着错误响应继续编答案")
                .isEqualTo("sk-platform-real-key");
        assertThat(existing.getName()).isEqualTo("改个名字");
    }

    @Test
    void 平台共享工具_传空白authHeader不覆盖既有凭据() {
        AiTool existing = AiTool.builder()
                .id(1L).name("shared_tool").scope(OwnerScope.PLATFORM).build();
        existing.setAuthHeader("Bearer platform-real-token");

        when(chatToolService.getTool(1L)).thenReturn(existing);
        when(chatToolService.saveSharedTool(any(AiTool.class))).thenReturn(existing);

        AiToolUpdateRequest req = new AiToolUpdateRequest();
        req.setName("改个名字");
        req.setAuthHeader("   ");

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requirePlatformAdmin).thenAnswer(inv -> null);

            platformController.updateSharedTool(1L, req);   // 直调真实控制器，而非复刻映射逻辑
        }

        assertThat(existing.getAuthHeader())
                .as("空白 authHeader 必须被当作「不修改」")
                .isEqualTo("Bearer platform-real-token");
        assertThat(existing.getName()).isEqualTo("改个名字");
    }

    // 反向断言：只钉「空白不覆盖」是空转的——若有人把整个 if 连同赋值一起删掉，
    // 上面两条测试照样绿（apiKey/authHeader 本来就没变过）。下面两条钉住另一半：
    // 传真实新凭据必须真的被写入，两个方向合起来才是完整的判空语义。

    @Test
    void 平台共享模型_传真实apiKey会写入新凭据() {
        AiModelConfig existing = AiModelConfig.builder()
                .id(1L).name("共享模型").provider("dashscope").modelId("qwen-max")
                .scope(OwnerScope.PLATFORM).build();
        existing.setApiKey("sk-platform-real-key");

        when(modelRouterService.getById(1L)).thenReturn(existing);
        when(modelRouterService.saveSharedModel(any(AiModelConfig.class))).thenReturn(existing);

        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setApiKey("sk-platform-rotated-key");   // 真实轮换凭据，而非空白

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requirePlatformAdmin).thenAnswer(inv -> null);

            platformController.updateSharedModel(1L, req);   // 直调真实控制器，而非复刻映射逻辑
        }

        assertThat(existing.getApiKey())
                .as("传入真实新凭据必须真的被写入，否则「空白不覆盖」的判空退化成「永不写入」")
                .isEqualTo("sk-platform-rotated-key");
    }

    @Test
    void 平台共享工具_传真实authHeader会写入新凭据() {
        AiTool existing = AiTool.builder()
                .id(1L).name("shared_tool").scope(OwnerScope.PLATFORM).build();
        existing.setAuthHeader("Bearer platform-real-token");

        when(chatToolService.getTool(1L)).thenReturn(existing);
        when(chatToolService.saveSharedTool(any(AiTool.class))).thenReturn(existing);

        AiToolUpdateRequest req = new AiToolUpdateRequest();
        req.setAuthHeader("Bearer platform-rotated-token");   // 真实轮换凭据，而非空白

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requirePlatformAdmin).thenAnswer(inv -> null);

            platformController.updateSharedTool(1L, req);   // 直调真实控制器，而非复刻映射逻辑
        }

        assertThat(existing.getAuthHeader())
                .as("传入真实新凭据必须真的被写入，否则「空白不覆盖」的判空退化成「永不写入」")
                .isEqualTo("Bearer platform-rotated-token");
    }
}
