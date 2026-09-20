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
    /**
     * 工具名上限，**直接复用 mapper 的 64**（OpenAI 兼容端点对函数名的限制），而不是
     * {@code AiTool.name} 的列宽 100。
     *
     * <p>⚠️ 这里曾经取 100「只防 DB 列宽溢出」，那留下一个静默失效面：候选名是**前端用户
     * 可改的**（导入面板鼓励改名——OpenAPI 的 summary 往往是写给人看的，不适合直接喂给
     * LLM），改到 65~100 字符时能存进库、界面也正常显示，但挂给 LLM 时被模型侧按 64 拒绝，
     * 症状是「工具配好了却从来不被调用」——无异常、无日志，与「LLM 觉得不需要调用」完全
     * 无法区分。
     *
     * <p>⚠️ **引用而非各自定义**：两处若各写一个字面量，改一处忘另一处就会重新裂开，而
     * 裂开的后果正是上面那种静默失效。
     */
    static final int MAX_NAME_LENGTH = OpenApiToolMapper.MAX_NAME;
    /** AiTool.description 的列宽 */
    static final int MAX_DESCRIPTION_LENGTH = 500;
    /** AiTool.urlTemplate 的列宽，同 OpenApiToolMapper.MAX_URL_TEMPLATE */
    static final int MAX_URL_TEMPLATE_LENGTH = 1000;
    /** AiTool.timeoutMs 的默认值：本期导入面板不暴露该配置，统一用与 create 端点一致的默认值 */
    static final int DEFAULT_TIMEOUT_MS = 5000;
    /** AiTool.maxResponseChars 的默认值，同上 */
    static final int DEFAULT_MAX_RESPONSE_CHARS = 4000;

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
            validateCandidate(c);
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
            t.setTimeoutMs(DEFAULT_TIMEOUT_MS);
            t.setMaxResponseChars(DEFAULT_MAX_RESPONSE_CHARS);
            t.setEnabled(request.getEnabled() == null || request.getEnabled());
            // ⚠️ 与 AiToolController.create 同款强制：scope 是客户端可控字段，不清掉的话
            // 租户能自封 PLATFORM，让自己的工具连同 authHeader 出现在所有租户的共享列表里。
            t.setScope(OwnerScope.TENANT);
            tools.add(t);
        }
        return toolRepository.saveAll(tools);
    }

    /**
     * 候选字段的显式校验：候选来自前端可编辑的表单（name/description 用户能改），或任意直接
     * 调用本接口的客户端（urlTemplate/httpMethod 前端不给编辑入口，但请求体层面完全可控）。
     * 不校验直接落库的后果：超出列宽时 {@code saveAll} 抛
     * {@code DataIntegrityViolationException}，被 {@code GlobalExceptionHandler} 接成
     * HTTP 500 + 完整 INSERT 语句回给客户端——同 docs/dev/子系统要点.md「MCP 接入」第 9 条记录的坑，换了
     * 个位置复发。
     */
    private void validateCandidate(OpenApiToolCandidate c) {
        if (c.getName() == null || c.getName().isBlank()) {
            throw new BusinessException("存在没有名字的工具，请先补全");
        }
        if (c.getName().length() > MAX_NAME_LENGTH) {
            // 带上理由：不写清楚的话，用户会以为这是个可以商量的界面限制，而它实际上是模型侧的
            // 硬约束——超了照样能存进库，只是永远不会被调用
            throw new BusinessException("工具名「" + c.getName() + "」超过 " + MAX_NAME_LENGTH
                    + " 个字符，请缩短。这是 AI 模型对函数名长度的限制，超出的工具不会被调用");
        }
        if (c.getDescription() == null || c.getDescription().isBlank()) {
            throw new BusinessException("工具「" + c.getName() + "」缺少描述，请先补全");
        }
        if (c.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException("工具「" + c.getName() + "」的描述超过 "
                    + MAX_DESCRIPTION_LENGTH + " 字，请缩短");
        }
        if (c.getUrlTemplate() == null || c.getUrlTemplate().isBlank()) {
            throw new BusinessException("工具「" + c.getName() + "」缺少 urlTemplate");
        }
        if (c.getUrlTemplate().length() > MAX_URL_TEMPLATE_LENGTH) {
            throw new BusinessException("工具「" + c.getName() + "」的 urlTemplate 超过 "
                    + MAX_URL_TEMPLATE_LENGTH + " 字，请缩短");
        }
        if (!"GET".equals(c.getHttpMethod()) && !"POST".equals(c.getHttpMethod())) {
            throw new BusinessException("工具「" + c.getName() + "」的 httpMethod 只能是 GET 或 POST");
        }
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
     * <p>⚠️ appId 为 null（未选应用）时必须显式短路、绝不能把 null 传给
     * {@code findByAppId}：{@code findByAppId} 是方法名派生查询（无 {@code @Query}），
     * Spring Data JPA 对派生查询的 null 参数会把等值条件**改写成 {@code IS NULL}**，
     * 不是「恒不匹配」的 {@code = NULL}。而 {@code X-App-Id} 在 {@code TenantInterceptor}
     * 里是可选头、{@code AiToolController} 没有角色限制，appId 为 null 常见于平台管理员
     * 场景（此时 tenantId 也为 null）——不短路的话，这条查询会变成
     * {@code app_id IS NULL}，把所有 app-less 的行（可能属于任意租户）当成「本应用自有」
     * 查出来，构成跨租户越权读取工具名。语义同 {@code ChatToolService.listTools()} 的
     * 显式短路，不能省略这一步。
     */
    private Set<String> existingNames() {
        Set<String> names = new LinkedHashSet<>();
        Long appId = TenantContext.getAppId();
        if (appId != null) {
            toolRepository.findByAppId(appId).forEach(t -> names.add(t.getName()));
        }
        toolRepository.findAllSharedTools().forEach(t -> names.add(t.getName()));
        return names;
    }
}
