package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.repository.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 开放 API 认证：校验 appKey/appSecret，返回对应应用。
 */
@Service
@RequiredArgsConstructor
public class OpenApiAuthService {

    private final ApplicationRepository applicationRepository;

    public Application authenticate(String appId, String apiKey) {
        if (appId == null || appId.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new OpenApiException(401, "缺少 X-App-Id / X-Api-Key");
        }
        Application app = applicationRepository.findByAppKey(appId)
                .orElseThrow(() -> new OpenApiException(401, "无效的凭据"));
        // 先判停用（403）再比对密钥（401）：停用应用短路更早、语义更清晰
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new OpenApiException(403, "应用已停用");
        }
        if (!apiKey.equals(app.getAppSecret())) {
            throw new OpenApiException(401, "无效的凭据");
        }
        return app;
    }
}
