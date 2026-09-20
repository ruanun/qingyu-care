package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link OpenAPI} → 候选工具列表。**纯函数**：给定 schema 与 baseUrl，输出完全确定，
 * 不碰 DB、不发请求。本特性的映射规则几乎全在这里，故单测也集中在这里。
 *
 * <p>⚠️ warnings 分两级，别混成一个列表：
 * <ul>
 *   <li><b>候选级</b>挂在具体候选上（描述截断、header 参数被忽略、$ref 降级），随它显示
 *       在勾选列表里；
 *   <li><b>全局级</b>描述「压根没能成为候选」的那些（PUT/DELETE 被跳过、requestBody 非
 *       object 被跳过、超出上限被截断）——它们没有候选行可挂，只做候选级 warning 的话这些
 *       operation 会<b>无声消失</b>，用户只会觉得「我那个端点怎么没导进来」。
 * </ul>
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class OpenApiToolMapper {

    /** 候选数量上限，防一份上千 operation 的 schema 撑爆预览界面 */
    static final int MAX_CANDIDATES = 200;
    /** AiTool.description 的列宽 */
    static final int MAX_DESCRIPTION = 500;
    /** ⚠️ 不是 AiTool.name 的列宽 100：64 是 OpenAI 兼容端点对函数名的限制 */
    static final int MAX_NAME = 64;
    /** $ref 递归深度上限，超出即降级为 object */
    static final int MAX_REF_DEPTH = 5;
    /**
     * AiTool.urlTemplate 的列宽。带 ~45 个以上 query 参数的端点就可能超（每个约
     * {@code name={name}} 2n+4 字符），报表/搜索类 API 达到这个量级不算罕见。不设防会让
     * {@code saveAll} 抛 {@code DataIntegrityViolationException}，被
     * {@code GlobalExceptionHandler} 接成 HTTP 500 + 完整 INSERT 语句回给客户端——同
     * docs/dev/子系统要点.md「MCP 接入」第 9 条记录的坑。
     */
    static final int MAX_URL_TEMPLATE = 1000;

    private final ObjectMapper objectMapper;

    public record MapResult(List<OpenApiToolCandidate> candidates,
                            List<String> globalWarnings,
                            boolean truncated) {
    }

    public MapResult map(OpenAPI api, String baseUrl) {
        List<OpenApiToolCandidate> candidates = new ArrayList<>();
        List<String> globalWarnings = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        boolean truncated = false;

        String base = normalizeBaseUrl(baseUrl, globalWarnings);
        if (api.getPaths() == null || api.getPaths().isEmpty()) {
            globalWarnings.add("schema 里没有任何路径");
            return new MapResult(candidates, globalWarnings, false);
        }

        outer:
        for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation op = opEntry.getValue();

                if (method != PathItem.HttpMethod.GET && method != PathItem.HttpMethod.POST) {
                    globalWarnings.add("已跳过 %s %s（%s）：本平台的工具只支持 GET 与 POST"
                            .formatted(method, path, op.getOperationId() == null ? "无 operationId" : op.getOperationId()));
                    continue;
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    truncated = true;
                    globalWarnings.add("端点数超过 " + MAX_CANDIDATES + " 个上限，只保留了前 "
                            + MAX_CANDIDATES + " 个，请分批导入");
                    break outer;
                }

                ToCandidateResult result = toCandidate(api, path, item, method, op, base, usedNames);
                if (result.candidate() == null) {
                    // toCandidate 不接收 globalWarnings（它只装候选级 warning），具体原因由
                    // result.skipReason() 给出——urlTemplate 超长、requestBody 引用无法解析、
                    // requestBody 不是对象结构，三种原因文案不同，不能再用一句话囫囵带过。
                    globalWarnings.add("已跳过 %s %s：%s".formatted(method, path, result.skipReason()));
                    continue;
                }
                candidates.add(result.candidate());
            }
        }
        return new MapResult(candidates, globalWarnings, truncated);
    }

    /**
     * {@link #toCandidate} 的结果：{@code candidate} 为 null 表示该 operation 无法表达、
     * 应跳过，此时 {@code skipReason} 给出具体原因，供 {@link #map} 拼进全局 warning。
     * 不能用一句写死的话囫囵带过——urlTemplate 超长、requestBody 引用无法解析、requestBody
     * 不是对象结构是三种不同的原因，文案含糊会把用户引向错误的排查方向。
     */
    private record ToCandidateResult(OpenApiToolCandidate candidate, String skipReason) {
        static ToCandidateResult of(OpenApiToolCandidate candidate) {
            return new ToCandidateResult(candidate, null);
        }

        static ToCandidateResult skip(String reason) {
            return new ToCandidateResult(null, reason);
        }
    }

    private ToCandidateResult toCandidate(OpenAPI api, String path, PathItem item, PathItem.HttpMethod method,
            Operation op, String baseUrl, Set<String> usedNames) {
        OpenApiToolCandidate c = new OpenApiToolCandidate();
        List<String> warnings = new ArrayList<>();

        c.setName(uniqueName(rawName(op, method, path), usedNames));
        c.setHttpMethod(method.name());
        c.setDescription(description(op, method, path, warnings));

        List<Parameter> params = mergeParameters(item, op, api.getComponents(), warnings);
        warnIgnoredParams(params, warnings);
        String urlTemplate = buildUrlTemplate(baseUrl, path, params);
        if (urlTemplate.length() > MAX_URL_TEMPLATE) {
            return ToCandidateResult.skip("urlTemplate 超过 " + MAX_URL_TEMPLATE + " 字上限（实际 "
                    + urlTemplate.length() + " 字，通常是参数过多导致），当前工具模型无法容纳，"
                    + "请精简参数或分多个端点导入");
        }
        c.setUrlTemplate(urlTemplate);

        SchemaResult schemaResult = buildInputSchema(api, op, params, method, warnings);
        if (schemaResult.schema() == null) {
            return ToCandidateResult.skip(schemaResult.skipReason());
        }
        c.setInputSchema(objectMapper.writeValueAsString(schemaResult.schema()));

        c.setWarnings(warnings);
        return ToCandidateResult.of(c);
    }

    // ===== 参数合并（路径级 + 操作级） =====

    /**
     * OpenAPI 3.x 允许在 {@code PathItem} 级别声明对该路径下所有 operation 都生效的共享
     * 参数（真实 schema 很常见：同一资源的多个方法共享 id 或过滤参数，Petstore 就有）。
     * 漏合并的后果比表面看起来更严重：路径级 <b>query</b> 参数会完全不出现在 urlTemplate
     * 与 inputSchema 里；路径级 <b>path</b> 参数虽然占位符还在 urlTemplate（直接来自 path
     * 字符串），但 inputSchema 里没有对应属性，LLM 不知道要填，运行时必然撞上
     * {@link com.qingyu.care.service.tool.UrlTemplateFiller} 的「缺少必填参数」——而工具
     * 调用是 fail-soft 的，模型会拿着错误文本继续编答案，全程无异常无日志。
     *
     * <p>⚠️ 合并<b>之前</b>必须先解引用：OpenAPI 允许参数写成 {@code {"$ref":
     * "#/components/parameters/X"}}（GitHub REST、Kubernetes 的官方 schema 几乎全是这种
     * 写法，是「共享参数定义」的标准做法）。swagger-core 把它反序列化成 {@code name}/{@code in}
     * 均为 null 的 {@link Parameter}，不解引用的话 {@link #paramKey} 会拿到一堆 {@code
     * "null:null"} 键，同路径下多个 $ref 参数会被互相当成同一参数吞掉；即便侥幸不撞键，
     * {@code in} 为 null 也会让该参数在后续 {@link #buildUrlTemplate}/{@link #buildInputSchema}
     * 里被判定成「既不是 path 也不是 query 也不是 header/cookie」而彻底消失，且没有任何
     * warning——这正是三种被 $ref 悄悄丢弃的失效模式（path 参数缺失必填、query 参数永远
     * 忽略、header 参数连「被忽略」的告警都不触发）的根源。
     *
     * <p>合并规则：路径级参数在前、操作级参数在后；操作级覆盖同名的路径级参数，同名判据是
     * {@code name} 与 {@code in} <b>两者都相同</b>——只比 name 会把「query 里的 id」与
     * 「header 里的 id」错误合并成一个参数。
     */
    private List<Parameter> mergeParameters(PathItem item, Operation op, Components components,
            List<String> warnings) {
        List<Parameter> pathParams = resolveParams(item.getParameters(), components, warnings);
        List<Parameter> opParams = resolveParams(op.getParameters(), components, warnings);
        if (pathParams.isEmpty()) {
            return opParams;
        }
        if (opParams.isEmpty()) {
            return pathParams;
        }
        Set<String> overriddenByOp = new LinkedHashSet<>();
        for (Parameter p : opParams) {
            overriddenByOp.add(paramKey(p));
        }
        List<Parameter> merged = new ArrayList<>();
        for (Parameter p : pathParams) {
            if (!overriddenByOp.contains(paramKey(p))) {
                merged.add(p);
            }
        }
        merged.addAll(opParams);
        return merged;
    }

    /**
     * 解引用 {@code $ref} 形式的参数，非 $ref 的原样返回。解不开的（引用前缀不对、
     * components 里没有 parameters、目标不存在）记<b>候选级</b> warning 并整个丢弃该参数
     * ——总比让一个 name/in 均为 null 的参数继续往下游流强，那会在 {@link #paramKey} 与
     * {@link #buildUrlTemplate} 里表现成更隐蔽的错误合并或静默消失。
     */
    private List<Parameter> resolveParams(List<Parameter> raw, Components components, List<String> warnings) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Parameter> resolved = new ArrayList<>();
        for (Parameter p : raw) {
            Parameter target = derefParameter(p, components, warnings);
            if (target != null) {
                resolved.add(target);
            }
        }
        return resolved;
    }

    /** @return 解引用后的参数；引用无法解析时返回 null（已记 warning） */
    private Parameter derefParameter(Parameter p, Components components, List<String> warnings) {
        String ref = p.get$ref();
        if (ref == null) {
            return p;
        }
        String prefix = "#/components/parameters/";
        if (!ref.startsWith(prefix) || components == null || components.getParameters() == null) {
            warnings.add("参数引用 " + ref + " 无法解析（只支持同文档内的 components/parameters），已忽略该参数");
            return null;
        }
        Parameter target = components.getParameters().get(ref.substring(prefix.length()));
        if (target == null) {
            warnings.add("参数引用 " + ref + " 指向的定义不存在，已忽略该参数");
            return null;
        }
        return target;
    }

    /** 参数同名判据：{@code in} 与 {@code name} 拼接，两者都相同才算同一参数。 */
    private String paramKey(Parameter p) {
        return p.getIn() + ":" + p.getName();
    }

    // ===== 名字 =====

    private String rawName(Operation op, PathItem.HttpMethod method, String path) {
        if (op.getOperationId() != null && !op.getOperationId().isBlank()) {
            return sanitize(op.getOperationId());
        }
        return sanitize(method.name().toLowerCase() + "_" + path);
    }

    /** 清洗成 [A-Za-z0-9_]，首字符非字母时前置 t_，截断到 {@link #MAX_NAME}。 */
    static String sanitize(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        if (s.isEmpty()) {
            s = "tool";
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "t_" + s;
        }
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME);
    }

    /** 批内重名加 _2/_3，且保证加完后仍不超过 {@link #MAX_NAME}。 */
    private String uniqueName(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "_" + n;
            String head = base.length() + suffix.length() <= MAX_NAME
                    ? base
                    : base.substring(0, MAX_NAME - suffix.length());
            String candidate = head + suffix;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    // ===== 描述 =====

    private String description(Operation op, PathItem.HttpMethod method, String path,
            List<String> warnings) {
        String text = op.getSummary();
        if (text == null || text.isBlank()) {
            text = op.getDescription();
        }
        if (text == null || text.isBlank()) {
            return method.name() + " " + path;
        }
        if (text.length() > MAX_DESCRIPTION) {
            warnings.add("描述过长已截断到 " + MAX_DESCRIPTION + " 字，建议手工改写得更贴合调用时机");
            return text.substring(0, MAX_DESCRIPTION);
        }
        return text;
    }

    // ===== urlTemplate =====

    /**
     * baseUrl + path + query 串。OpenAPI 的 path 占位符 {@code {orderId}} 与本项目
     * {@link com.qingyu.care.service.tool.UrlTemplateFiller} 的 {@code {key}} 语法完全一致，
     * 直接原样拼。query 参数按 schema 原始顺序追加（不按 required 重排——顺序稳定才可预期，
     * 且 UrlTemplateFiller 能正确处理任意位置的可选参数）。
     */
    private String buildUrlTemplate(String base, String path, List<Parameter> params) {
        StringBuilder url = new StringBuilder(base).append(path);
        String separator = "?";
        for (Parameter p : params) {
            if (!"query".equals(p.getIn())) {
                continue;
            }
            url.append(separator).append(p.getName()).append("={").append(p.getName()).append("}");
            separator = "&";
        }
        return url.toString();
    }

    /**
     * 规范化服务器地址：去掉末尾斜杠、剥掉 query。
     *
     * <p>⚠️ 剥 query 不是洁癖：拼接顺序是 base + path + {@code ?query}，base 自带 query 会
     * 拼出 {@code .../api?v=1/search&q={q}} 这种 path 跑到 query 后面的坏 URL，请求必然失败。
     * OpenAPI 的 {@code servers.url} 本就不该带 query，剥掉并告知比硬拼一个坏地址诚实。
     */
    private String normalizeBaseUrl(String baseUrl, List<String> globalWarnings) {
        if (baseUrl == null || baseUrl.isBlank()) {
            globalWarnings.add("schema 未提供绝对的服务器地址，请手工填写服务器地址后再导入");
            return "";
        }
        String base = baseUrl.trim();
        int q = base.indexOf('?');
        if (q >= 0) {
            globalWarnings.add("服务器地址里的查询参数已被忽略（" + base.substring(q)
                    + "）：它会让路径拼到查询串后面，请把这类参数写进各端点自身的参数里");
            base = base.substring(0, q);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * header / cookie 参数在 AiTool 模型里无法表达（只有一个静态 authHeader，没有按调用
     * 填充的 header 通道）。**必须记 warning**：少了认证 header 的工具会永远返回 401，而
     * 工具调用是 fail-soft 的——模型拿着错误响应继续编答案，全程无异常无日志。
     */
    private void warnIgnoredParams(List<Parameter> params, List<String> warnings) {
        List<String> ignored = params.stream()
                .filter(p -> "header".equals(p.getIn()) || "cookie".equals(p.getIn()))
                .map(Parameter::getName)
                .toList();
        if (!ignored.isEmpty()) {
            warnings.add("以下 header/cookie 参数无法表达、已忽略：" + String.join("、", ignored)
                    + "。若其中含认证信息，请在下方「鉴权头」里统一填写");
        }
    }

    // ===== inputSchema =====

    /**
     * 三个来源平铺合并成一个扁平 object：path 参数（全 required）、query 参数（按各自
     * required）、POST 的 application/json requestBody（**平铺到顶层**）。
     *
     * <p>平铺是被运行时逼出来的：{@link com.qingyu.care.service.tool.HttpToolCallback}
     * 在 POST 时把整个 toolInput 原样当 body 发出去，嵌套的 body 字段 LLM 永远填不对位置。
     *
     * @return schema 为 null 表示该 operation 无法表达、应整个跳过，此时 skipReason 给出具体原因
     */
    private SchemaResult buildInputSchema(OpenAPI api, Operation op, List<Parameter> params,
            PathItem.HttpMethod method, List<String> warnings) {
        Components components = api.getComponents();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        LinkedHashSet<String> required = new LinkedHashSet<>();

        for (Parameter p : params) {
            if (!"path".equals(p.getIn()) && !"query".equals(p.getIn())) {
                continue; // header/cookie 已在 warnIgnoredParams 里记过 warning
            }
            ObjectNode node = schemaToNode(p.getSchema(), components, new HashSet<>(), 0, warnings);
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                node.put("description", p.getDescription());
            }
            properties.set(p.getName(), node);
            // path 参数一律必填：URL 里填不出来就意味着这次调用根本不成立
            if ("path".equals(p.getIn()) || Boolean.TRUE.equals(p.getRequired())) {
                required.add(p.getName());
            }
        }

        // ⚠️ 只有 POST 才合并 requestBody：执行器只在 POST 时发 body，GET 合并进来只会让
        // LLM 填一堆到不了服务端的参数。
        if (method == PathItem.HttpMethod.POST && op.getRequestBody() != null) {
            String skipReason = mergeRequestBody(op.getRequestBody(), components, properties, required, warnings);
            if (skipReason != null) {
                return SchemaResult.skip(skipReason);
            }
        }

        if (!required.isEmpty()) {
            ArrayNode arr = root.putArray("required");
            required.forEach(arr::add);
        }
        return SchemaResult.of(root);
    }

    /**
     * {@link #buildInputSchema} 的结果：{@code schema} 为 null 表示无法表达，此时
     * {@code skipReason} 给出具体原因。
     */
    private record SchemaResult(ObjectNode schema, String skipReason) {
        static SchemaResult of(ObjectNode schema) {
            return new SchemaResult(schema, null);
        }

        static SchemaResult skip(String reason) {
            return new SchemaResult(null, reason);
        }
    }

    /**
     * @return null 表示合并成功；非 null 时是给全局 warning 用的具体跳过原因，调用方应跳过
     *     整个 operation。⚠️ 别用一句话囫囵带过三种不同的失败：requestBody 引用无法解析
     *     （见 {@link #derefRequestBody}）、非 JSON 请求体、JSON 但不是 object 结构——上一版
     *     把三者都说成「请求体不是对象结构」，第一种其实压根不是「结构」问题，会把用户引向
     *     错误的排查方向（去改 schema 的 body 定义，而不是去找那个引用指向哪）。
     */
    private String mergeRequestBody(RequestBody rawBody, Components components,
            ObjectNode properties, LinkedHashSet<String> required, List<String> warnings) {
        RequestBody body = derefRequestBody(rawBody, components);
        if (body == null) {
            return "请求体是无法解析的引用 " + rawBody.get$ref()
                    + "（只支持同文档内的 components/requestBodies），请手工补全该端点的请求体";
        }
        Content content = body.getContent();
        io.swagger.v3.oas.models.media.MediaType json =
                content == null ? null : content.get("application/json");
        if (json == null || json.getSchema() == null) {
            return "请求体不是 JSON 结构（form-urlencoded / multipart 等），当前工具模型无法表达";
        }
        Schema<?> resolved = derefSchema(json.getSchema(), components, new HashSet<>(), 0, warnings);
        if (resolved == null || resolved.getProperties() == null || resolved.getProperties().isEmpty()) {
            // ⚠️ 判据是「有 properties」而不是 type 字面量 == "object"：OpenAPI 3.1 里 type
            // 可以是数组（["object","null"]），而 swagger-models 的 getType() 只返回单值，
            // 按字面量判会把合法的 3.1 对象体误判成非对象。
            return "请求体不是对象结构，当前工具模型无法表达";
        }
        resolved.getProperties().forEach((key, value) -> {
            if (properties.has(key)) {
                warnings.add("请求体字段「" + key + "」与同名的 URL 参数冲突，已保留 URL 参数的定义");
                return;
            }
            properties.set(key, schemaToNode((Schema<?>) value, components, new HashSet<>(), 0, warnings));
        });
        if (resolved.getRequired() != null) {
            // 与 URL 参数同名而被跳过的 body 字段仍要进 required：properties 里有它（URL 参数
            // 那一版），且它确实是必填的。
            resolved.getRequired().forEach(required::add);
        }
        return null;
    }

    /**
     * 解引用 {@code $ref} 形式的 requestBody（同参数一样，OpenAPI 允许 requestBody 写成
     * {@code {"$ref": "#/components/requestBodies/X"}}）。非 $ref 的原样返回；解不开返回
     * null，由调用方决定用什么措辞告知用户——这里不直接记 warning，因为「引用无法解析」与
     * 「解开后发现不是 JSON/不是 object」需要落在同一条全局 warning 里，混进候选级 warnings
     * 会让同一个跳过原因出现在两个地方。
     */
    private RequestBody derefRequestBody(RequestBody rawBody, Components components) {
        String ref = rawBody.get$ref();
        if (ref == null) {
            return rawBody;
        }
        String prefix = "#/components/requestBodies/";
        if (!ref.startsWith(prefix) || components == null || components.getRequestBodies() == null) {
            return null;
        }
        return components.getRequestBodies().get(ref.substring(prefix.length()));
    }

    /**
     * 把 swagger 的 {@link Schema} 转成给 LLM 看的 JSON Schema 片段。
     *
     * <p>只提取有限字段（type/format/description/enum/items/properties/required），不直接
     * 序列化 Schema 对象——后者会带出一堆 null 字段与 {@code exampleSetFlag} 之类的内部噪声，
     * 白白占 LLM 的上下文。
     */
    private ObjectNode schemaToNode(Schema<?> schema, Components components,
            Set<String> refStack, int depth, List<String> warnings) {
        ObjectNode node = objectMapper.createObjectNode();
        Schema<?> resolved = derefSchema(schema, components, refStack, depth, warnings);
        if (resolved == null) {
            node.put("type", "object");
            return node;
        }
        String type = resolved.getType();
        if (type == null && resolved.getTypes() != null && !resolved.getTypes().isEmpty()) {
            type = resolved.getTypes().iterator().next(); // 3.1 的 type 可以是数组
        }
        node.put("type", type == null ? "string" : type);
        if (resolved.getFormat() != null) {
            node.put("format", resolved.getFormat());
        }
        if (resolved.getDescription() != null && !resolved.getDescription().isBlank()) {
            node.put("description", resolved.getDescription());
        }
        if (resolved.getEnum() != null && !resolved.getEnum().isEmpty()) {
            ArrayNode arr = node.putArray("enum");
            resolved.getEnum().forEach(v -> arr.add(String.valueOf(v)));
        }
        if (resolved.getItems() != null) {
            node.set("items", schemaToNode(resolved.getItems(), components, refStack, depth + 1, warnings));
        }
        if (resolved.getProperties() != null && !resolved.getProperties().isEmpty()) {
            ObjectNode props = node.putObject("properties");
            resolved.getProperties().forEach((k, v) ->
                    props.set(k, schemaToNode((Schema<?>) v, components, refStack, depth + 1, warnings)));
        }
        return node;
    }

    /**
     * 解本地 {@code #/components/schemas/X} 引用。跨文件与远程 $ref 一律不支持（那等于一条
     * 绕过 SsrfGuard 的出站路径，见设计文档 §2.1）。
     *
     * <p>⚠️ 递归返回后必须把 ref 从 {@code refStack} 里**移除**：否则两个兄弟字段引用同一个
     * schema（很常见，如两个字段都是 Address）会被误判成循环引用，第二个字段静默降级成
     * 空 object。栈语义 ≠ 访问过集合。
     */
    private Schema<?> derefSchema(Schema<?> schema, Components components,
            Set<String> refStack, int depth, List<String> warnings) {
        if (schema == null) {
            return null;
        }
        String ref = schema.get$ref();
        if (ref == null) {
            return schema;
        }
        if (depth >= MAX_REF_DEPTH) {
            warnings.add("引用嵌套过深（超过 " + MAX_REF_DEPTH + " 层），相关字段已降级为通用对象");
            return null;
        }
        if (!refStack.add(ref)) {
            warnings.add("检测到循环引用 " + ref + "，相关字段已降级为通用对象");
            return null;
        }
        try {
            String prefix = "#/components/schemas/";
            if (!ref.startsWith(prefix) || components == null || components.getSchemas() == null) {
                warnings.add("无法解析的引用 " + ref + "（只支持同文档内的 components/schemas），已降级为通用对象");
                return null;
            }
            Schema<?> target = components.getSchemas().get(ref.substring(prefix.length()));
            if (target == null) {
                warnings.add("引用 " + ref + " 指向的定义不存在，已降级为通用对象");
                return null;
            }
            return derefSchema(target, components, refStack, depth + 1, warnings);
        } finally {
            refStack.remove(ref);
        }
    }
}
