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
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI 导入的编排层：唯一碰数据库的组件。解析与映射都在无 IO 的纯组件里完成。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class OpenApiImportService {

    /** 一次导入的条数上限，防误操作把一份大 schema 整批灌进来 */
    static final int MAX_IMPORT = 50;

    private final OpenApiSchemaParser parser;
    private final OpenApiToolMapper mapper;
    private final OpenApiSchemaFetcher schemaFetcher;
    private final AiToolRepository toolRepository;

    /**
     * 解析并返回候选。**只读**：会查一次现有工具名做冲突标记，但绝不落任何行——用户会为
     * 调整配置反复解析同一份 schema，每次落库会留下大量垃圾。
     */
    public OpenApiParseResponse parse(OpenApiParseRequest request) {
        String text = request.getSchemaText();
        if (text == null || text.isBlank()) {
            if (request.getSchemaUrl() == null || request.getSchemaUrl().isBlank()) {
                throw new BusinessException("请粘贴 schema 内容或填写 schema 地址");
            }
            text = schemaFetcher.fetch(request.getSchemaUrl());
        }
        OpenAPI api = parser.parse(text);

        String baseUrl = request.getBaseUrl() != null && !request.getBaseUrl().isBlank()
                ? request.getBaseUrl().trim()
                : firstAbsoluteServer(api);

        OpenApiToolMapper.MapResult result = mapper.map(api, baseUrl);
        markConflicts(result.candidates());
        return new OpenApiParseResponse(result.candidates(), result.globalWarnings(), result.truncated());
    }

    @Transactional
    public List<AiTool> importTools(OpenApiImportRequest request) {
        List<OpenApiToolCandidate> candidates = request.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            throw new BusinessException("没有选中任何要导入的端点");
        }
        if (candidates.size() > MAX_IMPORT) {
            throw new BusinessException("一次最多导入 " + MAX_IMPORT + " 个工具，请分批操作");
        }
        // ⚠️ 再检一次冲突：parse 与 import 是两次请求，中间可能有并发创建；而且界面拦得住的
        // 东西接口层拦不住，门槛必须落在服务端。
        Set<String> taken = existingNames();
        Set<String> batch = new LinkedHashSet<>();
        for (OpenApiToolCandidate c : candidates) {
            if (c.getName() == null || c.getName().isBlank()) {
                throw new BusinessException("存在没有名字的工具，请先补全");
            }
            if (taken.contains(c.getName())) {
                throw new BusinessException("工具名「" + c.getName() + "」已存在，请改名后再导入");
            }
            if (!batch.add(c.getName())) {
                throw new BusinessException("本批里有重复的工具名「" + c.getName() + "」");
            }
        }

        List<AiTool> tools = new ArrayList<>();
        for (OpenApiToolCandidate c : candidates) {
            AiTool t = new AiTool();
            t.setName(c.getName());
            t.setDescription(c.getDescription());
            t.setHttpMethod(c.getHttpMethod());
            t.setUrlTemplate(c.getUrlTemplate());
            t.setInputSchema(c.getInputSchema());
            t.setAuthHeader(request.getAuthHeader());
            t.setTimeoutMs(request.getTimeoutMs() == null ? 5000 : request.getTimeoutMs());
            t.setMaxResponseChars(request.getMaxResponseChars() == null ? 4000 : request.getMaxResponseChars());
            t.setEnabled(request.getEnabled() == null || request.getEnabled());
            // ⚠️ 与 AiToolController.create 同款强制：scope 是客户端可控字段，不清掉的话
            // 租户能自封 PLATFORM，让自己的工具连同 authHeader 出现在所有租户的共享列表里。
            t.setScope(OwnerScope.TENANT);
            tools.add(t);
        }
        return toolRepository.saveAll(tools);
    }

    /** schema 的 servers[0]，只认绝对地址——相对路径（/v1）对出站请求毫无意义。 */
    private String firstAbsoluteServer(OpenAPI api) {
        if (api.getServers() == null || api.getServers().isEmpty()) {
            return "";
        }
        Server first = api.getServers().get(0);
        String url = first.getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "";
        }
        return url;
    }

    private void markConflicts(List<OpenApiToolCandidate> candidates) {
        Set<String> taken = existingNames();
        candidates.forEach(c -> c.setNameConflict(taken.contains(c.getName())));
    }

    /**
     * 已被占用的工具名 = 本应用自有 + 平台共享。
     *
     * <p>⚠️ 必须算上平台共享工具：{@code ChatToolService.resolve()} 遇到重名会静默跳过其中
     * 一个（只 log.warn），所以与共享工具重名的工具即便导进去了也挂不上 LLM。
     *
     * <p>appId 为 null（未选应用）时把 null 传给 {@code findByAppId} 不会抛异常——生成的
     * {@code app_id = NULL} 恒不匹配任何行，等价于「自有部分为空」，效果同
     * {@code ChatToolService.listTools()} 的显式短路。
     */
    private Set<String> existingNames() {
        Set<String> names = new LinkedHashSet<>();
        toolRepository.findByAppId(TenantContext.getAppId()).forEach(t -> names.add(t.getName()));
        toolRepository.findAllSharedTools().forEach(t -> names.add(t.getName()));
        return names;
    }
}
