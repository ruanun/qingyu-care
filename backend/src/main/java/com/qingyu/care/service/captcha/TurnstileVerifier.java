package com.qingyu.care.service.captcha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 向 Cloudflare 校验 Turnstile 令牌。
 *
 * <p>目标地址写死、不来自用户输入，故不走 {@code SsrfGuard}（与 AI SDK 直连同理）。
 * 令牌由 Cloudflare 保证一次性，服务端不必再记重放。
 *
 * @author ruan
 */
@Slf4j
@Component
public class TurnstileVerifier {

    static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final RestClient client;

    public TurnstileVerifier() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Cloudflare 的判定结果。
     *
     * @param errorCodes Cloudflare 给的错误码，能区分「令牌无效/过期」与「secret 配错」（invalid-input-secret）
     * @param hostname   令牌签发时所在页面的主机名，通过时才有
     */
    public record Result(boolean success, List<String> errorCodes, String hostname) {
    }

    /**
     * @return Cloudflare 的判定结果（通过或不通过）
     * @throws IllegalStateException 网络故障或响应无法解析——调用方须按「无法验证」处理，而不是放行
     */
    public Result verify(String secretKey, String token, String remoteIp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank()) {
            form.add("remoteip", remoteIp);
        }
        Map<?, ?> body;
        try {
            body = client.post()
                    .uri(SITEVERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Turnstile 校验请求失败: " + e.getClass().getSimpleName(), e);
        }
        if (body == null) {
            throw new IllegalStateException("Turnstile 校验响应为空");
        }
        boolean success = Boolean.TRUE.equals(body.get("success"));
        List<String> errorCodes = body.get("error-codes") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (!success) {
            log.info("Turnstile 校验未通过: {}", errorCodes);
        }
        Object hostname = body.get("hostname");
        return new Result(success, errorCodes, hostname == null ? null : hostname.toString());
    }
}
