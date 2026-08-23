package com.qingyu.care.service.openapi;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.openapi.OpenApiImportRequest;
import com.qingyu.care.dto.openapi.OpenApiParseRequest;
import com.qingyu.care.dto.openapi.OpenApiParseResponse;
import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiToolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层：取文本 → 解析 → 映射 → 冲突标记 / 批量落库。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class OpenApiImportServiceTest {

    private static final Long APP_ID = 1L;

    @Mock
    private OpenApiSchemaFetcher schemaFetcher;
    @Mock
    private AiToolRepository toolRepository;

    private OpenApiImportService service;

    private static final String SCHEMA = """
            {"openapi":"3.0.0","info":{"title":"t","version":"1"},
             "servers":[{"url":"https://api.example.com"}],
             "paths":{"/orders":{"get":{"operationId":"listOrders","summary":"列订单"}}}}""";

    private OpenApiImportService service() {
        ObjectMapper om = new ObjectMapper();
        return new OpenApiImportService(new OpenApiSchemaParser(), new OpenApiToolMapper(om),
                schemaFetcher, toolRepository);
    }

    // ⚠️ TenantContext 是静态 ThreadLocal，Surefire 同线程顺序跑同一个类里的多个测试方法，
    // 不清理会把这条测试设的 appId 残留给下一条——同「@Async 里必须 clear()」那条规矩。
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void 粘贴文本时不发任何出站请求() {
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates()).hasSize(1);
        verify(schemaFetcher, never()).fetch(any());
    }

    @Test
    void 未填文本也未填URL时报错() {
        service = service();

        assertThatThrownBy(() -> service.parse(new OpenApiParseRequest()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void baseUrl留空时取schema的servers第一项() {
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/orders");
    }

    @Test
    void appId为null时不查自有工具() {
        // 本方法故意不调用 TenantContext.setAppId：appId 缺省（如平台管理员未选应用）时
        // 必须短路、不能查 findByAppId——它是方法名派生查询，null 参数会被 Spring Data JPA
        // 改写成 app_id IS NULL 而非恒不匹配，不短路就会把任意租户的 app-less 行当成
        // 「本应用自有」查出来，见 OpenApiImportService#existingNames 的注释。
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates()).hasSize(1);
        verify(toolRepository, never()).findByAppId(any());
    }

    @Test
    void 与既有工具重名的候选被标记冲突() {
        service = service();
        TenantContext.setAppId(APP_ID);
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        AiTool existing = new AiTool();
        existing.setName("listOrders");
        when(toolRepository.findByAppId(any())).thenReturn(List.of(existing));
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).isNameConflict()).isTrue();
    }

    @Test
    void 与平台共享工具重名同样算冲突() {
        // ChatToolService.resolve() 里同名工具会被静默跳过其中一个（只 log.warn），
        // 所以共享工具的名字同样是「占用」状态，不检出来的话导进去也挂不上。
        // 不需要设 appId：本测试只关心共享工具这一路，自有工具列表始终为空。
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        AiTool shared = new AiTool();
        shared.setName("listOrders");
        when(toolRepository.findAllSharedTools()).thenReturn(List.of(shared));

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).isNameConflict()).isTrue();
    }

    @Test
    void 导入时强制租户归属且套用统一配置() {
        // 不需要设 appId：本测试不关心冲突检测，自有工具列表始终为空。
        service = service();
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());
        when(toolRepository.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));

        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(List.of(candidate("listOrders")));
        req.setAuthHeader("Authorization: Bearer x");
        req.setTimeoutMs(8000);
        req.setMaxResponseChars(2000);
        req.setEnabled(true);

        service.importTools(req);

        ArgumentCaptor<List<AiTool>> captor = ArgumentCaptor.forClass(List.class);
        verify(toolRepository).saveAll(captor.capture());
        AiTool saved = captor.getValue().get(0);
        // ⚠️ 文档性断言，不构成回归保护：OpenApiToolCandidate 与 OpenApiImportRequest 都
        // 没有 scope 字段，客户端结构性地没有通道覆盖它——把 importTools() 里那行
        // setScope(TENANT) 整个删掉，AiTool.scope 字段自带的初始化器
        // `= OwnerScope.TENANT` 会让本断言照样通过。真正的保护来自这条结构事实本身；
        // 这里只是记录「此路径落库必须是 TENANT」这个契约，与直接收 AiTool 实体、
        // scope 客户端可写的 create 端点不是同一类风险。
        assertThat(saved.getScope()).isEqualTo(OwnerScope.TENANT);
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer x");
        assertThat(saved.getTimeoutMs()).isEqualTo(8000);
        assertThat(saved.getId()).isNull();
    }

    @Test
    void 导入时再检一次冲突_有冲突则整批拒绝() {
        // parse 与 import 是两次请求，中间可能有并发创建；且界面拦得住的东西接口层拦不住
        service = service();
        TenantContext.setAppId(APP_ID);
        AiTool existing = new AiTool();
        existing.setName("listOrders");
        when(toolRepository.findByAppId(any())).thenReturn(List.of(existing));
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(List.of(candidate("listOrders")));

        assertThatThrownBy(() -> service.importTools(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("listOrders");
        verify(toolRepository, never()).saveAll(any());
    }

    @Test
    void 超过50条时拒绝整批() {
        service = service();
        List<OpenApiToolCandidate> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(candidate("tool" + i));
        }
        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(many);

        assertThatThrownBy(() -> service.importTools(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50");
    }

    @Test
    void 空候选列表被拒() {
        service = service();
        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(List.of());

        assertThatThrownBy(() -> service.importTools(req))
                .isInstanceOf(BusinessException.class);
    }

    private OpenApiToolCandidate candidate(String name) {
        OpenApiToolCandidate c = new OpenApiToolCandidate();
        c.setName(name);
        c.setDescription("说明");
        c.setHttpMethod("GET");
        c.setUrlTemplate("https://api.example.com/orders");
        c.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        return c;
    }
}
