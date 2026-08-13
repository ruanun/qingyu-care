package com.qingyu.care.controller;

import com.qingyu.care.dto.AiSkillUpdateRequest;
import com.qingyu.care.dto.AiToolUpdateRequest;
import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 局部更新不得把没传的字段打回类默认值。
 *
 * <p>这些测试守的是一类<b>不报错、只静默毁数据</b>的缺陷：实体带 Lombok
 * {@code @Builder.Default} 时，默认值也会被写进无参构造，而 Jackson 反序列化走的就是那个
 * 构造——请求体没给的字段拿到类默认值而非 {@code null}，{@code if (x != null)} 恒真。
 * 专用 DTO 不写任何字段初始化器，判空才成立。
 *
 * <p><b>断言必须检查具体原值</b>（如 {@code isEqualTo(30000)}），不能只断言非 null——
 * 后者在字段被打回默认值时同样成立，是永真测试。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PartialUpdateSemanticsTest {

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
    void 技能局部更新不清空已绑定的工具() {
        AiSkill existing = AiSkill.builder().id(1L).name("退货助手").build();
        existing.setToolIds(new LinkedHashSet<>(Set.of(7L, 9L)));

        AiSkillUpdateRequest req = new AiSkillUpdateRequest();
        req.setEnabled(false);   // 前端点停用开关只发这一个字段

        // 复刻控制器的映射逻辑
        if (req.getName() != null) existing.setName(req.getName());
        if (req.getToolIds() != null) existing.setToolIds(req.getToolIds());
        if (req.getEnabled() != null) existing.setEnabled(req.getEnabled());

        assertThat(existing.getToolIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(existing.getEnabled()).isFalse();
    }

    @Test
    void 租户局部更新不把套餐打回FREE() {
        Tenant existing = Tenant.builder().id(1L).name("云舟科技").build();
        existing.setPlan(TenantPlan.ENTERPRISE);
        existing.setStatus(TenantStatus.ACTIVE);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.FROZEN);   // 前端冻结租户只发 status

        if (req.getName() != null) existing.setName(req.getName());
        if (req.getStatus() != null) existing.setStatus(req.getStatus());
        if (req.getPlan() != null) existing.setPlan(req.getPlan());

        assertThat(existing.getPlan()).isEqualTo(TenantPlan.ENTERPRISE);
        assertThat(existing.getStatus()).isEqualTo(TenantStatus.FROZEN);
    }

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
