package com.qingyu.care.entity;

import com.qingyu.care.enums.UserRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link User#getPassword()} 绝不能序列化进响应。
 *
 * <p>安全审查 M1（2026-09-03）：{@code UserController} 直接把 {@code User} 实体当响应体，
 * 密码的 BCrypt 哈希随用户列表返回给同租户所有成员（平台管理员的 {@code findAll} 更是全平台），
 * 可离线爆破。修法是 {@code @JsonProperty(access = WRITE_ONLY)}——只挡序列化方向，
 * 不影响其它路径把 {@code password} 反序列化进来。
 *
 * @author ruan
 */
class UserPasswordNotSerializedTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 序列化用户时不含password字段() {
        User u = User.builder().id(1L).username("zhangsan")
                .password("$2a$10$SOME_HASH").role(UserRole.TENANT_MEMBER).build();

        String json = om.writeValueAsString(u);

        assertThat(json).doesNotContain("password").doesNotContain("SOME_HASH");
        assertThat(json).contains("zhangsan");
    }

    /** 守住「是 WRITE_ONLY 而非 @JsonIgnore」：反序列化方向仍要能收到 password。 */
    @Test
    void 反序列化仍能读入password() {
        User u = om.readValue("{\"username\":\"a\",\"password\":\"plain\"}", User.class);

        assertThat(u.getPassword()).isEqualTo("plain");
    }
}
