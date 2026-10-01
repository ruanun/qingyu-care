package com.qingyu.care.dto.open;

import com.qingyu.care.entity.Application;
import lombok.Builder;
import lombok.Data;

/**
 * Widget 公开配置响应：仅暴露渲染挂件所需的品牌信息（无任何密钥）。
 *
 * @author ruan
 */
@Data
@Builder
public class OpenWidgetConfigResponse {

    /** 应用名称 */
    private String appName;

    /** 欢迎语 */
    private String welcomeMessage;

    /** 头像 URL */
    private String avatar;

    /** 主题色 */
    private String themeColor;

    /**
     * 卡片图片允许自动加载的域名（{@code *.example.com} 形式支持子域）；不在其中的图片只显示为链接。
     * 空列表表示一律不自动加载（2026-09-30 安全审查 M7）。
     */
    private java.util.List<String> imageDomains;

    public static OpenWidgetConfigResponse of(Application app) {
        return of(app, null);
    }

    /**
     * @param brandName 租户配置的对外品牌名（tenant.display.name），非空时覆盖应用名对外展示
     */
    public static OpenWidgetConfigResponse of(Application app, String brandName) {
        return OpenWidgetConfigResponse.builder()
                .appName(brandName != null && !brandName.isBlank() ? brandName : app.getName())
                .welcomeMessage(app.getWelcomeMessage())
                .avatar(app.getAvatar())
                .themeColor(app.getThemeColor())
                .imageDomains(app.getImageAllowedDomains() == null || app.getImageAllowedDomains().isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(app.getImageAllowedDomains().split(",")))
                .build();
    }
}
