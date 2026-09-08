package com.qingyu.care.service;

import java.util.List;
import java.util.Locale;

/**
 * OpenAI 兼容厂商的预设清单。纯数据 + 查找，无状态、不进 Spring 容器。
 *
 * <p><b>为什么清单在后端而不是前端</b>：{@link ModelRouterService#createChatClient} 对所有
 * provider 走同一条 OpenAI 兼容路径，provider 字段的唯一实际用途就是查这张表拿默认端点。
 * 前端只是把同一份数据渲染成下拉与徽章。本仓库反复栽在「同一份清单在前后端各写一次然后
 * 各自漂移」上（卡片 Action 协议白名单分三处、导出勾选过滤前后端各一份），所以这里只留
 * 一份，前端经 {@code GET /v1/ai-models/providers} 拉取。
 *
 * <p><b>⚠️ 端点后缀不统一，这正是本表存在的理由</b>：智谱是 {@code /api/paas/v4}、火山方舟是
 * {@code /api/v3}、Groq 是 {@code /openai/v1}、Gemini 是 {@code /v1beta/openai}。
 * Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 {@code chat/completions} 与
 * {@code embeddings}，<b>不补 {@code /v1}</b>——所以本表每一项都必须是「拼上
 * {@code chat/completions} 就是正确完整地址」的形态，且不能带尾斜杠（会拼出 {@code //}）。
 * 刻意不做「缺 /v1 就自动补全」的归一化：404 是可见错误，自动补全只会掩盖配置错误，
 * 而且对上面四家补 {@code /v1} 本身就是错的。
 *
 * <p><b>⚠️ 两个填表时容易踩的坑</b>：
 * <ul>
 *   <li><b>coding 套餐是另一套端点，且与通用端点并非同一套服务</b>。智谱、火山方舟、阶跃星辰
 *       都有独立的编码套餐路径（如火山的 {@code /api/coding/v3}）。本表一律给通用路径，用
 *       套餐的人必须自己改。两处差异都踩得到：① 计费口径不同，填成通用路径会按量计费、
 *       不扣套餐额度，只有账单上看得出来；② <b>可用模型集合也不同</b>——2026-09-08 真机实测，
 *       同一个 key、同一个模型名，打 {@code /api/coding/v3/embeddings} 正常返回向量，打
 *       {@code /api/v3/embeddings} 却报 {@code ModelNotOpen}「账号未激活该模型服务」。
 *       所以「换成通用端点试试」并不是一个安全的排查动作，它可能把一个能用的配置改成不能用的。
 *   <li><b>Ollama 指向环回地址</b>，生产环境会被 {@link com.qingyu.care.config.SsrfGuard}
 *       在落库前拒绝，须打开 {@code MODEL_ALLOW_PRIVATE_ENDPOINTS}。这是 fail-fast 的，
 *       保存时就会报「模型端点无效」，不是静默失败。
 * </ul>
 *
 * <p><b>{@code sampleModelId} 只是界面占位示例，不是校验白名单</b>：各家模型 id 漂移很快，
 * 拿它做校验会让新模型发布当天所有人都配不上。
 *
 * <p>不在本表里的厂商走界面上的「自定义」，provider 与端点都自由填——后端对 provider
 * 从不做白名单校验，本表只影响默认端点与界面展示。
 *
 * @author ruan
 */
public final class ModelProviderCatalog {

    /**
     * 一个厂商预设。
     *
     * @param key             程序内标识，小写，落库进 {@code ai_model_config.provider}
     * @param label           界面显示名
     * @param defaultEndpoint OpenAI 兼容 base URL，无尾斜杠
     * @param sampleModelId   界面占位示例，非校验白名单
     */
    public record ProviderPreset(String key, String label, String defaultEndpoint, String sampleModelId) {
    }

    /** 兜底端点：未知 provider 一律回落这里，与改造前 switch 的 default 分支同义。 */
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1";

    /**
     * ⚠️ 前四项的 defaultEndpoint 值被 {@code ModelProviderCatalogTest} 逐字钉死，别顺手改。
     * {@link ModelRouterService} 的 {@code keyOf()} 拿它给 endpoint 留空的模型行做去重归一化，
     * 改了会让存量的那些行在去重时换组，而这个失效完全静默。
     */
    private static final List<ProviderPreset> PRESETS = List.of(
            new ProviderPreset("openai", "OpenAI", OPENAI_ENDPOINT, "gpt-4o-mini"),
            new ProviderPreset("dashscope", "通义千问 百炼",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.7-flash-2026-07-15"),
            new ProviderPreset("moonshot", "Kimi 月之暗面",
                    "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            new ProviderPreset("deepseek", "DeepSeek",
                    "https://api.deepseek.com/v1", "deepseek-chat"),
            // ↓ 以下为 2026-09-08 新增，端点均经官方文档核实
            new ProviderPreset("zhipu", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.7"),
            new ProviderPreset("siliconflow", "硅基流动",
                    "https://api.siliconflow.cn/v1", "Pro/deepseek-ai/DeepSeek-R1"),
            new ProviderPreset("volcengine", "火山方舟 豆包",
                    "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-251015"),
            new ProviderPreset("openrouter", "OpenRouter",
                    "https://openrouter.ai/api/v1", "openai/gpt-4o"),
            new ProviderPreset("groq", "Groq",
                    "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
            new ProviderPreset("gemini", "Google Gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash"),
            new ProviderPreset("xai", "xAI Grok",
                    "https://api.x.ai/v1", "grok-4.6"),
            new ProviderPreset("ollama", "Ollama 本地",
                    "http://localhost:11434/v1", "qwen3:8b")
    );

    private ModelProviderCatalog() {
    }

    /** 全部预设，不可变。 */
    public static List<ProviderPreset> presets() {
        return PRESETS;
    }

    /**
     * 取该 provider 的默认端点。大小写不敏感，容忍首尾空白；
     * null / 空白 / 未登记的 provider 一律回落 OpenAI 官方端点。
     */
    public static String defaultEndpointFor(String provider) {
        if (provider == null || provider.isBlank()) {
            return OPENAI_ENDPOINT;
        }
        String key = provider.trim().toLowerCase(Locale.ROOT);
        return PRESETS.stream()
                .filter(p -> p.key().equals(key))
                .map(ProviderPreset::defaultEndpoint)
                .findFirst()
                .orElse(OPENAI_ENDPOINT);
    }
}
