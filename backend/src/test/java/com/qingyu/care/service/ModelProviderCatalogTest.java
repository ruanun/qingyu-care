package com.qingyu.care.service;

import com.qingyu.care.service.ModelProviderCatalog.ProviderPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厂商预设清单的单测。
 *
 * <p>这不是走过场的清单校验：{@link ModelRouterService} 的 {@code keyOf()} 拿
 * {@code defaultEndpoint} 给 endpoint 留空的模型行做去重归一化，某个已有 provider 的
 * 默认端点一旦变了，存量里那些行会在去重时换组——而这个失效完全静默。
 * 故四个既有 provider 的端点值在这里被逐字钉死。
 *
 * @author ruan
 */
class ModelProviderCatalogTest {

    @Test
    @DisplayName("四个既有 provider 的默认端点逐字不变（守 keyOf 去重归一化）")
    void keeps_legacy_default_endpoints_verbatim() {
        assertThat(ModelProviderCatalog.defaultEndpointFor("openai"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(ModelProviderCatalog.defaultEndpointFor("dashscope"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(ModelProviderCatalog.defaultEndpointFor("moonshot"))
                .isEqualTo("https://api.moonshot.cn/v1");
        assertThat(ModelProviderCatalog.defaultEndpointFor("deepseek"))
                .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @DisplayName("未知 provider 回落 OpenAI 官方端点，与改造前的 switch default 分支同义")
    void unknown_provider_falls_back_to_openai() {
        assertThat(ModelProviderCatalog.defaultEndpointFor("zzz-never-heard-of"))
                .isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("provider 匹配不分大小写，且容忍首尾空白")
    void lookup_is_case_insensitive_and_trims() {
        assertThat(ModelProviderCatalog.defaultEndpointFor("ZhiPu"))
                .isEqualTo(ModelProviderCatalog.defaultEndpointFor("zhipu"));
        assertThat(ModelProviderCatalog.defaultEndpointFor("  groq  "))
                .isEqualTo(ModelProviderCatalog.defaultEndpointFor("groq"));
    }

    @Test
    @DisplayName("null / 空白 provider 不抛异常，回落 OpenAI 端点")
    void blank_provider_falls_back_without_throwing() {
        assertThat(ModelProviderCatalog.defaultEndpointFor(null))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(ModelProviderCatalog.defaultEndpointFor("   "))
                .isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("key 全局唯一")
    void keys_are_unique() {
        Set<String> seen = new HashSet<>();
        for (ProviderPreset p : ModelProviderCatalog.presets()) {
            assertThat(seen.add(p.key())).as("重复的 provider key: %s", p.key()).isTrue();
        }
    }

    @Test
    @DisplayName("清单非空，且包含本次新增的八家")
    void catalog_contains_expected_providers() {
        List<String> keys = ModelProviderCatalog.presets().stream().map(ProviderPreset::key).toList();
        // 先断言非空：若 presets() 退化成空表，下面的逐项断言会跟着一起消失而不是转红
        assertThat(keys).isNotEmpty();
        assertThat(keys).contains(
                "openai", "dashscope", "moonshot", "deepseek",
                "zhipu", "siliconflow", "volcengine", "openrouter",
                "groq", "gemini", "xai", "ollama");
    }

    @Test
    @DisplayName("每项字段完整：key 小写、显示名与示例模型非空")
    void every_preset_is_fully_populated() {
        assertThat(ModelProviderCatalog.presets()).isNotEmpty();
        for (ProviderPreset p : ModelProviderCatalog.presets()) {
            assertThat(p.key()).isNotBlank().isEqualTo(p.key().toLowerCase());
            assertThat(p.label()).as("%s 缺显示名", p.key()).isNotBlank();
            assertThat(p.sampleModelId()).as("%s 缺示例模型 id", p.key()).isNotBlank();
        }
    }

    @Test
    @DisplayName("端点不带尾斜杠——openai-java 拼 chat/completions 时会拼出 // 而 404")
    void endpoints_have_no_trailing_slash() {
        assertThat(ModelProviderCatalog.presets()).isNotEmpty();
        for (ProviderPreset p : ModelProviderCatalog.presets()) {
            assertThat(p.defaultEndpoint()).as("%s 的端点带尾斜杠", p.key()).doesNotEndWith("/");
        }
    }

    @Test
    @DisplayName("只有本地 Ollama 可以是 http，其余一律 https")
    void only_local_ollama_may_use_plain_http() {
        assertThat(ModelProviderCatalog.presets()).isNotEmpty();
        for (ProviderPreset p : ModelProviderCatalog.presets()) {
            if ("ollama".equals(p.key())) {
                assertThat(p.defaultEndpoint()).startsWith("http://");
            } else {
                assertThat(p.defaultEndpoint()).as("%s 必须走 https", p.key()).startsWith("https://");
            }
        }
    }

    @Test
    @DisplayName("四家后缀不是 /v1 的厂商，端点后缀逐字钉死——填错就是静默 404")
    void pins_the_four_non_v1_suffixes() {
        assertThat(ModelProviderCatalog.defaultEndpointFor("zhipu"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(ModelProviderCatalog.defaultEndpointFor("volcengine"))
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
        assertThat(ModelProviderCatalog.defaultEndpointFor("groq"))
                .isEqualTo("https://api.groq.com/openai/v1");
        assertThat(ModelProviderCatalog.defaultEndpointFor("gemini"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
    }

    @Test
    @DisplayName("presets() 交出的清单不可被调用方改坏")
    void presets_are_immutable() {
        List<ProviderPreset> list = ModelProviderCatalog.presets();
        try {
            list.add(new ProviderPreset("x", "X", "https://x.test/v1", "m", null));
            org.junit.jupiter.api.Assertions.fail("清单应当是不可变的");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
    }

    @Test
    @DisplayName("只有 OpenAI 官方用 max_completion_tokens，其余厂商走 max_tokens")
    void max_completion_tokens_is_openai_only() {
        assertThat(ModelProviderCatalog.usesMaxCompletionTokens("openai")).isTrue();
        // 大小写与首尾空白容忍：库里存量行的 provider 列不一定干净
        assertThat(ModelProviderCatalog.usesMaxCompletionTokens(" OpenAI ")).isTrue();
        // 国内兼容模式普遍只认 max_tokens，给错字段是 400
        assertThat(ModelProviderCatalog.usesMaxCompletionTokens("dashscope")).isFalse();
        assertThat(ModelProviderCatalog.usesMaxCompletionTokens("zhipu")).isFalse();
        assertThat(ModelProviderCatalog.usesMaxCompletionTokens(null)).isFalse();
        assertThat(ModelProviderCatalog.usesMaxCompletionTokens("  ")).isFalse();
    }
}
