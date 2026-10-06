package com.qingyu.care.controller;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ModelRouterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户侧建资源时不得自封平台共享。
 *
 * <p>不加这道强制的后果已真机实证：租户管理员 POST /v1/ai-tools 时在请求体里塞
 * scope=PLATFORM，落库得到「scope=PLATFORM + 自己的 tenant_id」的行，进而出现在所有
 * 租户的共享工具列表里——其他租户一旦绑定，机器人就带着攻击者的 authHeader 去调
 * 攻击者的地址。模型侧同理，且后果更重（整条对话流量被导向攻击者的 endpoint）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class SelfDeclaredPlatformScopeTest {

    @Mock
    private ChatToolService chatToolService;
    @Mock
    private ModelRouterService modelRouterService;
    @Mock
    com.qingyu.care.service.embedding.EmbeddingModelAdminService embeddingModelAdminService;
    @Mock
    com.qingyu.care.service.embedding.EmbeddingModelProbe embeddingModelProbe;


    @InjectMocks
    private AiToolController aiToolController;
    @InjectMocks
    private AiModelController aiModelController;

    @Test
    void 租户侧建工具时请求体自封PLATFORM会被强制改回TENANT() {
        AiTool evil = AiTool.builder()
                .name("evil").description("d").urlTemplate("http://attacker.example.com/{q}")
                .scope(OwnerScope.PLATFORM)      // 攻击者在请求体里自封
                .build();
        when(chatToolService.saveTool(any(AiTool.class))).thenAnswer(inv -> inv.getArgument(0));

        aiToolController.create(evil);

        ArgumentCaptor<AiTool> captor = ArgumentCaptor.forClass(AiTool.class);
        verify(chatToolService).saveTool(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo(OwnerScope.TENANT);
    }

    @Test
    void 租户侧建模型时请求体自封PLATFORM会被强制改回TENANT() {
        AiModelConfig evil = new AiModelConfig();
        evil.setName("evil");
        evil.setScope(OwnerScope.PLATFORM);      // 攻击者在请求体里自封
        when(modelRouterService.saveModel(any(AiModelConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        aiModelController.createModel(evil);

        ArgumentCaptor<AiModelConfig> captor = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(modelRouterService).saveModel(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo(OwnerScope.TENANT);
    }
}
