package com.qingyu.care.service.provider;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ModelProviderUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ModelProviderRepository;
import com.qingyu.care.service.ConfigScopeResolver;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.embedding.EmbeddingModelAdminService;
import com.qingyu.care.service.embedding.EmbeddingModelProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 供应商连接：归属校验、凭据只在连接上改并同步到模型、改凭据前探测向量模型、在连接下建模型。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ModelProviderServiceTest {

    @Mock ModelProviderRepository providerRepository;
    @Mock AiModelConfigRepository modelRepository;
    @Mock ModelRouterService modelRouterService;
    @Mock EmbeddingModelAdminService embeddingModelAdminService;
    @Mock EmbeddingModelProbe embeddingModelProbe;
    @Mock RemoteModelLister remoteModelLister;
    @Mock ConfigScopeResolver configScopeResolver;
    @InjectMocks ModelProviderService service;

    @BeforeEach
    void setUp() {
        lenient().when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(10L, 20L));
        lenient().when(providerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(10L);
        TenantContext.setAppId(20L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ModelProvider tenantProvider() {
        return ModelProvider.builder().id(5L).tenantId(10L).scope(OwnerScope.TENANT).name("通义")
                .providerKey("dashscope").endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey("sk-OLD").build();
    }

    private static ModelProvider platformProvider() {
        return ModelProvider.builder().id(6L).tenantId(null).scope(OwnerScope.PLATFORM).name("平台通义")
                .providerKey("dashscope").apiKey("sk-PLATFORM").build();
    }

    // ===== 归属 =====

    @Test
    void 别的租户的连接_按不存在拒绝() {
        ModelProvider other = tenantProvider();
        other.setTenantId(99L);
        when(providerRepository.findById(5L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.requireOwned(5L, OwnerScope.TENANT)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 租户侧拿不到平台连接_不能拿平台的Key调任意模型() {
        when(providerRepository.findById(6L)).thenReturn(Optional.of(platformProvider()));

        assertThatThrownBy(() -> service.requireOwned(6L, OwnerScope.TENANT)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.requireOwned(6L, OwnerScope.PLATFORM).getId()).isEqualTo(6L);
    }

    @Test
    void 无法确定租户时拒绝建连接_否则会凭空多出一个平台连接() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, null));
        ModelProvider req = ModelProvider.builder().name("x").providerKey("openai").build();

        assertThatThrownBy(() -> service.create(req, OwnerScope.TENANT)).isInstanceOf(InvalidOperationException.class);
        verify(providerRepository, never()).save(any());
    }

    // ===== 建 / 改 =====

    @Test
    void 建连接_端点先过守卫再落库_归属由服务端决定() {
        ModelProvider req = ModelProvider.builder().name(" 通义 ").providerKey("dashscope")
                .endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1").apiKey("sk-x").build();
        req.setTenantId(99L);              // 请求体里自带的归属一律不认
        req.setScope(OwnerScope.PLATFORM);

        ModelProvider saved = service.create(req, OwnerScope.TENANT);

        verify(modelRouterService).requireValidEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(saved.getTenantId()).isEqualTo(10L);
        assertThat(saved.getScope()).isEqualTo(OwnerScope.TENANT);
        assertThat(saved.getName()).isEqualTo("通义");
    }

    @Test
    void 建连接_端点非法则不落库() {
        doThrow(new InvalidOperationException("模型端点无效")).when(modelRouterService).requireValidEndpoint("http://127.0.0.1/v1");
        ModelProvider req = ModelProvider.builder().name("x").providerKey("openai").endpoint("http://127.0.0.1/v1").build();

        assertThatThrownBy(() -> service.create(req, OwnerScope.TENANT)).isInstanceOf(InvalidOperationException.class);
        verify(providerRepository, never()).save(any());
    }

    @Test
    void 改连接_空白Key视为不修改_也不触发同步() {
        ModelProvider p = tenantProvider();
        when(providerRepository.findById(5L)).thenReturn(Optional.of(p));
        ModelProviderUpdateRequest req = new ModelProviderUpdateRequest();
        req.setName("改个名字");
        req.setApiKey("   ");   // 编辑弹窗不回显 Key，粘贴还可能带来全空格

        service.update(5L, req, OwnerScope.TENANT);

        assertThat(p.getApiKey()).as("空白 Key 必须当作「不修改」，否则其下所有模型的凭据被抹掉").isEqualTo("sk-OLD");
        assertThat(p.getName()).isEqualTo("改个名字");
        verify(modelRepository, never()).syncFromProvider(any(), any(), any(), any());
    }

    @Test
    void 改连接_真实新Key会写入并同步到其下全部模型() {
        ModelProvider p = tenantProvider();
        when(providerRepository.findById(5L)).thenReturn(Optional.of(p));
        when(modelRepository.findByProviderIdAcrossApps(5L)).thenReturn(List.of());
        ModelProviderUpdateRequest req = new ModelProviderUpdateRequest();
        req.setApiKey("sk-ROTATED");

        service.update(5L, req, OwnerScope.TENANT);

        assertThat(p.getApiKey()).isEqualTo("sk-ROTATED");
        verify(modelRepository).syncFromProvider(5L, "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-ROTATED");
    }

    @Test
    void 改连接_新端点非法则不落库不同步() {
        when(providerRepository.findById(5L)).thenReturn(Optional.of(tenantProvider()));
        doThrow(new InvalidOperationException("模型端点无效")).when(modelRouterService).requireValidEndpoint("http://169.254.169.254/v1");
        ModelProviderUpdateRequest req = new ModelProviderUpdateRequest();
        req.setEndpoint("http://169.254.169.254/v1");

        assertThatThrownBy(() -> service.update(5L, req, OwnerScope.TENANT)).isInstanceOf(InvalidOperationException.class);
        verify(providerRepository, never()).save(any());
        verify(modelRepository, never()).syncFromProvider(any(), any(), any(), any());
    }

    @Test
    void 改Key前用新凭据探测其下向量模型_不过就整体拒绝() {
        ModelProvider p = tenantProvider();
        when(providerRepository.findById(5L)).thenReturn(Optional.of(p));
        AiModelConfig emb = AiModelConfig.builder().id(7L).name("通义向量").modelId("qwen3.7-text-embedding")
                .modelType(ModelType.EMBEDDING).build();
        AiModelConfig chat = AiModelConfig.builder().id(8L).name("对话").modelId("qwen-max").modelType(ModelType.CHAT).build();
        when(modelRepository.findByProviderIdAcrossApps(5L)).thenReturn(List.of(emb, chat));
        doThrow(new InvalidOperationException("上游返回 HTTP 401")).when(embeddingModelProbe).requireCompatible(any());
        ModelProviderUpdateRequest req = new ModelProviderUpdateRequest();
        req.setApiKey("sk-WRONG");

        assertThatThrownBy(() -> service.update(5L, req, OwnerScope.TENANT))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("通义向量");

        ArgumentCaptor<AiModelConfig> cap = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(embeddingModelProbe, times(1)).requireCompatible(cap.capture());   // 只探向量模型
        assertThat(cap.getValue().getApiKey()).isEqualTo("sk-WRONG");
        assertThat(p.getApiKey()).isEqualTo("sk-OLD");
        verify(modelRepository, never()).syncFromProvider(any(), any(), any(), any());
    }

    @Test
    void 删连接_其下还有模型就拒绝() {
        when(providerRepository.findById(5L)).thenReturn(Optional.of(tenantProvider()));
        when(modelRepository.findByProviderIdAcrossApps(5L)).thenReturn(List.of(AiModelConfig.builder().id(1L).build()));

        assertThatThrownBy(() -> service.delete(5L, OwnerScope.TENANT)).isInstanceOf(InvalidOperationException.class);
        verify(providerRepository, never()).delete(any());
    }

    // ===== 在连接下建模型 =====

    @Test
    void 建模型_凭据从连接复制_请求体里的端点与Key不算数() {
        ModelProvider p = tenantProvider();
        when(modelRouterService.saveModel(any())).thenAnswer(inv -> inv.getArgument(0));
        AiModelConfig req = AiModelConfig.builder().name("m").modelId("qwen-max")
                .endpoint("https://evil.example/v1").apiKey("sk-EVIL").scope(OwnerScope.PLATFORM).build();

        AiModelConfig saved = service.createModel(req, p, OwnerScope.TENANT);

        assertThat(saved.getProviderId()).isEqualTo(5L);
        assertThat(saved.getEndpoint()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(saved.getApiKey()).isEqualTo("sk-OLD");
        assertThat(saved.getProvider()).isEqualTo("dashscope");
        assertThat(saved.getScope()).as("租户侧自封 PLATFORM 必须被改回").isEqualTo(OwnerScope.TENANT);
        verify(embeddingModelAdminService).prepareCreate(saved);
    }

    @Test
    void 批量添加_单项失败不影响其他项_已存在的跳过() {
        when(providerRepository.findById(5L)).thenReturn(Optional.of(tenantProvider()));
        when(modelRepository.findByProviderIdAndAppId(5L, 20L)).thenReturn(List.of(
                AiModelConfig.builder().modelId("qwen-max").modelType(ModelType.CHAT).build()));
        when(modelRouterService.saveModel(any())).thenAnswer(inv -> {
            AiModelConfig m = inv.getArgument(0);
            m.setId(100L);
            return m;
        });
        doAnswer(inv -> {
            AiModelConfig m = inv.getArgument(0);
            if ("text-embedding-v1".equals(m.getModelId())) {
                throw new InvalidOperationException("向量维度不符：该模型返回 1536 维");
            }
            return null;
        }).when(embeddingModelAdminService).prepareCreate(any());

        List<ModelProviderService.AddResult> results = service.addModels(5L, List.of(
                new ModelProviderService.NewModel("qwen-max", ModelType.CHAT, null),
                new ModelProviderService.NewModel("text-embedding-v1", ModelType.EMBEDDING, null),
                new ModelProviderService.NewModel("qwen3.7-text-embedding", ModelType.EMBEDDING, "通义向量")
        ), OwnerScope.TENANT);

        assertThat(results).extracting(ModelProviderService.AddResult::ok).containsExactly(false, false, true);
        assertThat(results.get(0).message()).contains("已存在");
        assertThat(results.get(1).message()).contains("向量维度不符");
        verify(modelRouterService, times(1)).saveModel(any());
    }

    @Test
    void 批量添加_租户侧没选应用就拒绝() {
        TenantContext.setAppId(null);
        when(providerRepository.findById(5L)).thenReturn(Optional.of(tenantProvider()));

        assertThatThrownBy(() -> service.addModels(5L, List.of(new ModelProviderService.NewModel("m", ModelType.CHAT, null)),
                OwnerScope.TENANT)).isInstanceOf(InvalidOperationException.class);
        verify(modelRouterService, never()).saveModel(any());
    }
}
