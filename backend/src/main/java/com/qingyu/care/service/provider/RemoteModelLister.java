package com.qingyu.care.service.provider;

import com.qingyu.care.config.ModelEndpointGuardInterceptor;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.service.ModelProviderCatalog;
import com.qingyu.care.service.ModelRouterService;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拉取供应商的模型列表：OpenAI 兼容的 {@code GET {base}/models}。同时充当「测试连接」。
 *
 * <p>出站与模型调用走<b>同一道防线</b>：挂 {@link ModelEndpointGuardInterceptor}（禁重定向、连接期 DNS 校验，
 * 私网开关与模型端点一致），另加总时长上限与响应体上限——端点是租户填的，对端回一个无穷大的 body
 * 或者一直滴字节，都不能拖住请求线程或撑爆内存（安全基线第 39、40 条的同一类问题）。
 *
 * <p>失败原因只给分类、不回显上游原文（同测试连接，安全审查 M1）。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class RemoteModelLister {

    /** 模型列表一般几十 KB；1MB 足够大的厂商（OpenRouter 几百个模型）也够用 */
    static final long MAX_BODY_BYTES = 1024 * 1024;

    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;

    /** 一个远端模型，附带按名称预判的类型（只是默认勾选，界面可改）。 */
    public record RemoteModel(String id, ModelType suggestedType) {
    }

    public List<RemoteModel> list(ModelProvider provider) {
        String base = provider.getEndpoint();
        if (base == null || base.isBlank()) {
            base = ModelProviderCatalog.defaultEndpointFor(provider.getProviderKey());
        }
        String url = base.endsWith("/") ? base + "models" : base + "/models";
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(modelRouterService.endpointGuardInterceptor())
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(15))
                .build();
        Request.Builder request = new Request.Builder().url(url).get();
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            request.header("Authorization", "Bearer " + provider.getApiKey().trim());
        }
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new InvalidOperationException("上游返回 HTTP " + response.code()
                        + (response.code() == 404 ? "（该厂商可能不提供模型列表接口，可手动填写模型 ID）"
                        : "（请检查端点地址与 API Key）"));
            }
            return parse(readBounded(response.body()));
        } catch (InvalidOperationException e) {
            throw e;
        } catch (ModelEndpointGuardInterceptor.EndpointRejectedException e) {
            throw new InvalidOperationException(e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new InvalidOperationException("无法连接上游（" + e.getClass().getSimpleName() + "）");
        }
    }

    static String readBounded(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        BufferedSource source = body.source();
        // request 返回 false 表示流在凑够这么多字节之前就结束了，也就是没超限
        if (source.request(MAX_BODY_BYTES + 1)) {
            throw new InvalidOperationException("模型列表响应过大，已放弃解析，可手动填写模型 ID");
        }
        return source.getBuffer().readUtf8();
    }

    List<RemoteModel> parse(String json) {
        JsonNode data;
        try {
            data = objectMapper.readTree(json).path("data");
        } catch (RuntimeException e) {
            throw new InvalidOperationException("模型列表不是 OpenAI 兼容格式，可手动填写模型 ID");
        }
        if (!data.isArray()) {
            throw new InvalidOperationException("模型列表不是 OpenAI 兼容格式，可手动填写模型 ID");
        }
        List<RemoteModel> out = new ArrayList<>();
        for (JsonNode node : data) {
            String id = node.path("id").asString("");
            if (id.isBlank()) {
                continue;
            }
            ModelType type = suggestType(id);
            if (type != null) {
                out.add(new RemoteModel(id, type));
            }
        }
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return out;
    }

    /**
     * 按名称预判类型：只决定界面上的默认分组，最终以用户勾选为准（向量模型保存时还有真实探测兜底）。
     * 重排 / 语音 / 图像生成等本平台用不上的类型返回 null，不列出来。
     */
    static ModelType suggestType(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("rerank") || s.contains("whisper") || s.contains("tts") || s.contains("speech")
                || s.contains("dall-e") || s.contains("image") || s.contains("moderation") || s.contains("asr")) {
            return null;
        }
        if (s.contains("embed") || s.contains("bge-") || s.contains("/bge") || s.startsWith("bge")
                || s.contains("m3e") || s.contains("gte-") || s.contains("e5-")) {
            return ModelType.EMBEDDING;
        }
        return ModelType.CHAT;
    }
}
