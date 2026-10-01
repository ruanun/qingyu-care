package com.qingyu.care.controller;

import com.qingyu.care.exception.InvalidOperationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 卡片图片域名白名单的落库归一化（2026-09-30 安全审查 M7）。
 *
 * @author ruan
 */
class ApplicationImageDomainsTest {

    @Test
    void 去空白转小写去重_支持中文逗号与换行() {
        assertThat(ApplicationController.normalizeImageDomains(" IMG.example.com，*.cdn.example.com\nimg.example.com "))
                .isEqualTo("img.example.com,*.cdn.example.com");
    }

    @Test
    void 空串表示清空() {
        assertThat(ApplicationController.normalizeImageDomains("  ")).isEmpty();
        assertThat(ApplicationController.normalizeImageDomains(null)).isNull();
    }

    /** 裸 * 等于关掉防线；带协议、路径、端口的写法按主机名匹配时永远命中不了，落库就拦，免得配了等于没配 */
    @Test
    void 拒绝裸通配与带协议路径端口的写法() {
        for (String bad : new String[]{"*", "*.com", "https://img.example.com", "img.example.com/a.png",
                "img.example.com:8080", "evil.com?x=1"}) {
            assertThatThrownBy(() -> ApplicationController.normalizeImageDomains(bad))
                    .as(bad).isInstanceOf(InvalidOperationException.class);
        }
    }
}
