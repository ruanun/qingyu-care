package com.qingyu.care.controller;

import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.dto.AiSkillUpdateRequest;
import com.qingyu.care.dto.AiToolUpdateRequest;
import com.qingyu.care.dto.ApplicationUpdateRequest;
import com.qingyu.care.dto.ChatStrategyUpdateRequest;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.webhook.WebhookPayloadBuilder;
import com.qingyu.care.service.webhook.WebhookSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * 只有 {@code UserController} 因权限判断依赖登录态、mock 成本明显更高，仍保留复刻形态，见该
 * 测试方法上的说明。
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

    /**
     * ⚠️ 本测试复刻了 {@code UserController.updateUser} 密码分支的映射逻辑（
     * {@code if (request.getPassword() != null && !request.getPassword().isEmpty())}），
     * 没有直调真实控制器——{@code UserController} 的更新路径依赖 {@code StpUtil.getSession()}
     * 读取登录态判断管理员权限，mock 成本明显高于 Skill/Tenant/AiModel/Application/ChatStrategy
     * 那几条（它们的控制器只依赖可直接注入的 service/repository）。
     *
     * <p>两份代码会漂移：改动 {@code UserController.updateUser} 第 174 行那个判断时，必须同步
     * 改这里，否则本测试会静默失去判别力（继续绿，但守不住真实控制器里的退化）。
     */
    @Test
    void 编辑用户时传空串密码不改动密码哈希() {
        User existing = User.builder().id(1L).username("zhangsan").build();
        existing.setPassword("$2a$10$OLD_HASH_PLACEHOLDER");
        existing.setNickname("旧昵称");

        UserUpdateRequest req = new UserUpdateRequest();
        req.setNickname("新昵称");
        req.setPassword("");   // 前端编辑弹窗把 password 初始化为空串并照样提交

        if (req.getNickname() != null) existing.setNickname(req.getNickname());
        // 与 UserController 里一致：必须同时判空串
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            existing.setPassword("HASHED_" + req.getPassword());
        }

        assertThat(existing.getNickname()).isEqualTo("新昵称");
        assertThat(existing.getPassword()).isEqualTo("$2a$10$OLD_HASH_PLACEHOLDER");
    }
}
