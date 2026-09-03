package com.qingyu.care.service;

import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型 endpoint 是租户任填的 URL，出站前必须过 SSRF 守卫。
 *
 * <p>安全审查 M4（2026-09-03）：{@code createOpenAiCompatibleClient} 直接 {@code baseUrl(endpoint)}，
 * 「测试连接」会向该地址发一次真实 POST 并回显异常——租户填 {@code http://100.100.100.200/}
 * （阿里云元数据）或 {@code http://postgres:5432} 就能探测内网。校验放在管理面入口
 * （建/改/测试连接），不放在每轮对话的热路径上；{@code allow-private-endpoints} 只给本地跑
 * 自托管模型（Ollama 等）的开发环境用。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ModelRouterServiceEndpointGuardTest {

    @Mock
    AiModelConfigRepository repository;
    @InjectMocks
    ModelRouterService service;

    @Test
    void 指向内网的端点被拒绝() {
        assertThatThrownBy(() -> service.requireValidEndpoint("http://127.0.0.1:11434/v1"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("端点");
    }

    @Test
    void 非http协议的端点被拒绝() {
        assertThatThrownBy(() -> service.requireValidEndpoint("ftp://8.8.8.8/v1"))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void 公网端点通过() {
        assertThatCode(() -> service.requireValidEndpoint("https://8.8.8.8/v1")).doesNotThrowAnyException();
    }

    /** 空端点表示沿用默认（application.yml 的 base-url），不是错误。 */
    @Test
    void 空端点视为使用默认() {
        assertThatCode(() -> service.requireValidEndpoint(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.requireValidEndpoint("  ")).doesNotThrowAnyException();
    }

    @Test
    void 放开私网端点时只校验形态() {
        ReflectionTestUtils.setField(service, "allowPrivateEndpoints", true);

        assertThatCode(() -> service.requireValidEndpoint("http://127.0.0.1:11434/v1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireValidEndpoint("ftp://127.0.0.1/v1"))
                .isInstanceOf(InvalidOperationException.class);
    }
}
