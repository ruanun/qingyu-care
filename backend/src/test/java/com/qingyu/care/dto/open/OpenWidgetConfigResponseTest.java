package com.qingyu.care.dto.open;

import com.qingyu.care.entity.Application;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Widget 配置响应的品牌名覆盖单测（租户配置 tenant.display.name）。
 *
 * @author ruan
 */
class OpenWidgetConfigResponseTest {

    private Application app() {
        return Application.builder()
                .id(1L)
                .name("客服机器人")
                .welcomeMessage("你好")
                .avatar("http://a/avatar.png")
                .themeColor("#123456")
                .build();
    }

    @Test
    void 配置了品牌名_覆盖应用名对外展示() {
        OpenWidgetConfigResponse resp = OpenWidgetConfigResponse.of(app(), "青语科技");

        assertThat(resp.getAppName()).isEqualTo("青语科技");
        assertThat(resp.getWelcomeMessage()).isEqualTo("你好");
    }

    @Test
    void 品牌名为空或空白_回落应用名() {
        assertThat(OpenWidgetConfigResponse.of(app(), null).getAppName()).isEqualTo("客服机器人");
        assertThat(OpenWidgetConfigResponse.of(app(), "  ").getAppName()).isEqualTo("客服机器人");
        assertThat(OpenWidgetConfigResponse.of(app()).getAppName()).isEqualTo("客服机器人");
    }
}
