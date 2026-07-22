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
                .build();
    }
}
