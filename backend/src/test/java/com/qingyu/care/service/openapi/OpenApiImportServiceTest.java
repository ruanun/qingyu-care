package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiImportRequest;
import com.qingyu.care.dto.openapi.OpenApiParseRequest;
import com.qingyu.care.dto.openapi.OpenApiParseResponse;
import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiToolRepository;
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

    @Test
    void 粘贴文本时不发任何出站请求() {
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
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
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/orders");
    }

    @Test
    void 与既有工具重名的候选被标记冲突() {
        service = service();
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
        // 所以共享工具的名字同样是「占用」状态，不检出来的话导进去也挂不上
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        AiTool shared = new AiTool();
        shared.setName("listOrders");
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of(shared));

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).isNameConflict()).isTrue();
    }

    @Test
    void 导入时强制租户归属且套用统一配置() {
        service = service();
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
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
        // scope 是客户端可控字段，必须由服务层强制 TENANT——不清掉的话租户能自封 PLATFORM，
        // 让自己的工具连同 authHeader 出现在所有租户的共享列表里（ai_tool 上的真实事故）
        assertThat(saved.getScope()).isEqualTo(OwnerScope.TENANT);
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer x");
        assertThat(saved.getTimeoutMs()).isEqualTo(8000);
        assertThat(saved.getId()).isNull();
    }

    @Test
    void 导入时再检一次冲突_有冲突则整批拒绝() {
        // parse 与 import 是两次请求，中间可能有并发创建；且界面拦得住的东西接口层拦不住
        service = service();
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
