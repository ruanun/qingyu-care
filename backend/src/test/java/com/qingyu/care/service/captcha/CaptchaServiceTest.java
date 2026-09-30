package com.qingyu.care.service.captcha;

import com.qingyu.care.enums.CaptchaMode;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 注册验证码：模式解析、图形验证码一次性消费、Turnstile 结果映射、两者 fail-closed、配置保存校验。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class CaptchaServiceTest {

    @Mock ConfigService configService;
    @Mock TurnstileVerifier turnstileVerifier;
    @Mock ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    CaptchaService service;
    final Map<String, String> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new CaptchaService(configService, turnstileVerifier, redisProvider);
        // 桩成真实的「库里有就返回、没有或空白给默认值」，各用例只往 stored 里放值
        lenient().when(configService.get(eq(OwnerScope.PLATFORM), isNull(), anyString(), any()))
                .thenAnswer(inv -> {
                    String v = stored.get(inv.<String>getArgument(2));
                    return v == null || v.isBlank() ? inv.getArgument(3) : v;
                });
        lenient().when(redisProvider.getIfAvailable()).thenReturn(redis);
        lenient().when(redis.opsForValue()).thenReturn(ops);
    }

    private void mode(CaptchaMode m) {
        stored.put(CaptchaService.MODE_KEY, m.name());
    }

    private static TurnstileVerifier.Result pass() {
        return new TurnstileVerifier.Result(true, List.of(), "example.com");
    }

    private static TurnstileVerifier.Result reject(String... codes) {
        return new TurnstileVerifier.Result(false, List.of(codes), null);
    }

    private static int code(Throwable e) {
        return ((BusinessException) e).getCode();
    }

    // ---------- 模式解析 ----------

    @Test
    void 未配置时不启用() {
        assertThat(service.currentMode()).isEqualTo(CaptchaMode.NONE);
    }

    @Test
    void 大小写不敏感() {
        stored.put(CaptchaService.MODE_KEY, " turnstile ");
        assertThat(service.currentMode()).isEqualTo(CaptchaMode.TURNSTILE);
    }

    /** 非法值只可能来自直接改库；此时按图形验证码处理，而不是悄悄关掉 */
    @Test
    void 非法值按图形验证码处理() {
        stored.put(CaptchaService.MODE_KEY, "recaptcha");
        assertThat(service.currentMode()).isEqualTo(CaptchaMode.IMAGE);
    }

    // ---------- 不启用 ----------

    @Test
    void 不启用时不做任何校验() {
        assertThatCode(() -> service.verify(null, null, null, "1.2.3.4")).doesNotThrowAnyException();
        verifyNoInteractions(redisProvider, turnstileVerifier);
    }

    // ---------- 图形验证码 ----------

    @Test
    void 生成图形验证码_答案写入Redis且带过期_返回可解码的PNG() throws Exception {
        mode(CaptchaMode.IMAGE);

        CaptchaService.ImageCaptcha captcha = service.generateImage();

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(CaptchaService.REDIS_KEY_PREFIX + captcha.captchaId()), answer.capture(), eq(CaptchaService.TTL));
        assertThat(answer.getValue()).hasSize(4).matches("[A-Z2-9]{4}");

        assertThat(captcha.image()).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(captcha.image().substring("data:image/png;base64,".length()));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(120);
        assertThat(img.getHeight()).isEqualTo(40);
    }

    @Test
    void 未启用图形验证码时拒绝出图() {
        mode(CaptchaMode.TURNSTILE);
        assertThatThrownBy(() -> service.generateImage()).satisfies(e -> assertThat(code(e)).isEqualTo(400));
        verifyNoInteractions(redisProvider);
    }

    @Test
    void 图形验证码答对放行_大小写与首尾空白不敏感() {
        mode(CaptchaMode.IMAGE);
        when(ops.getAndDelete(CaptchaService.REDIS_KEY_PREFIX + "abc")).thenReturn("K7PX");

        assertThatCode(() -> service.verify("abc", " k7px ", null, null)).doesNotThrowAnyException();
    }

    /**
     * 必须取出即删：用 get 而非 getAndDelete 时，同一个 captchaId 可以无限次重猜，4 位验证码几分钟就能穷举。
     * 变异实验：把 getAndDelete 改成 get，本条转红。
     */
    @Test
    void 图形验证码答错也作废_只能用一次() {
        mode(CaptchaMode.IMAGE);
        when(ops.getAndDelete(CaptchaService.REDIS_KEY_PREFIX + "abc")).thenReturn("K7PX");

        assertThatThrownBy(() -> service.verify("abc", "WRONG", null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400));

        verify(ops).getAndDelete(CaptchaService.REDIS_KEY_PREFIX + "abc");
        verify(ops, never()).get(anyString());
    }

    @Test
    void 图形验证码过期或不存在时拒绝() {
        mode(CaptchaMode.IMAGE);
        when(ops.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.verify("gone", "K7PX", null, null))
                .hasMessage("验证码错误或已过期");
    }

    @Test
    void 图形验证码没填时拒绝且不碰Redis() {
        mode(CaptchaMode.IMAGE);
        assertThatThrownBy(() -> service.verify("abc", " ", null, null)).hasMessage("请输入验证码");
        assertThatThrownBy(() -> service.verify(null, "K7PX", null, null)).hasMessage("请输入验证码");
        verifyNoInteractions(redisProvider);
    }

    /** fail-closed：Redis 挂了不能放行，否则验证码形同虚设 */
    @Test
    void Redis故障时拒绝而不是放行() {
        mode(CaptchaMode.IMAGE);
        when(ops.getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.verify("abc", "K7PX", null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(503));
    }

    @Test
    void 没有Redis时拒绝出图也拒绝校验() {
        mode(CaptchaMode.IMAGE);
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.generateImage()).satisfies(e -> assertThat(code(e)).isEqualTo(503));
        assertThatThrownBy(() -> service.verify("abc", "K7PX", null, null)).satisfies(e -> assertThat(code(e)).isEqualTo(503));
    }

    // ---------- Turnstile ----------

    @Test
    void Turnstile通过时放行_把secret令牌与客户端IP传给Cloudflare() {
        mode(CaptchaMode.TURNSTILE);
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "sec");
        when(turnstileVerifier.verify("sec", "tok", "1.2.3.4")).thenReturn(pass());

        assertThatCode(() -> service.verify(null, null, "tok", "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void Turnstile判定不通过时拒绝() {
        mode(CaptchaMode.TURNSTILE);
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "sec");
        when(turnstileVerifier.verify(any(), any(), any())).thenReturn(reject("invalid-input-response"));

        assertThatThrownBy(() -> service.verify(null, null, "tok", null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400));
    }

    @Test
    void Turnstile没有令牌时拒绝且不请求Cloudflare() {
        mode(CaptchaMode.TURNSTILE);
        assertThatThrownBy(() -> service.verify(null, null, "", null)).hasMessage("请先完成人机验证");
        verifyNoInteractions(turnstileVerifier);
    }

    /** fail-closed：Cloudflare 不可达不能放行 */
    @Test
    void Cloudflare不可达时拒绝而不是放行() {
        mode(CaptchaMode.TURNSTILE);
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "sec");
        when(turnstileVerifier.verify(any(), any(), any())).thenThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> service.verify(null, null, "tok", null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(503));
    }

    @Test
    void 未配置Secret时拒绝而不是放行() {
        mode(CaptchaMode.TURNSTILE);
        assertThatThrownBy(() -> service.verify(null, null, "tok", null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(503));
        verifyNoInteractions(turnstileVerifier);
    }

    // ---------- 配置保存校验 ----------

    @Test
    void 保存非法验证方式被拒() {
        Map<String, String> in = new HashMap<>(Map.of(CaptchaService.MODE_KEY, "recaptcha"));
        assertThatThrownBy(() -> service.validateConfigUpdate(in)).satisfies(e -> assertThat(code(e)).isEqualTo(400));
    }

    @Test
    void 保存时把验证方式规范成大写() {
        Map<String, String> in = new HashMap<>(Map.of(CaptchaService.MODE_KEY, "image"));
        service.validateConfigUpdate(in);
        assertThat(in).containsEntry(CaptchaService.MODE_KEY, "IMAGE");
    }

    /** 选了 Turnstile 却没配 secret：放过去注册入口就整个不可用，而保存时毫无提示 */
    @Test
    void 切到Turnstile但密钥没配齐时拒绝保存() {
        Map<String, String> onlySite = new HashMap<>(Map.of(
                CaptchaService.MODE_KEY, "TURNSTILE", CaptchaService.TURNSTILE_SITE_KEY, "site"));
        assertThatThrownBy(() -> service.validateConfigUpdate(onlySite)).satisfies(e -> assertThat(code(e)).isEqualTo(400));

        Map<String, String> blankSite = new HashMap<>(Map.of(
                CaptchaService.MODE_KEY, "TURNSTILE", CaptchaService.TURNSTILE_SITE_KEY, " ",
                CaptchaService.TURNSTILE_SECRET_KEY, "sec"));
        assertThatThrownBy(() -> service.validateConfigUpdate(blankSite)).satisfies(e -> assertThat(code(e)).isEqualTo(400));
    }

    /** secret 只写不读，编辑表单留空提交（调用方已剔除该键）时要拿库里的值来判断 */
    @Test
    void 库里已有Secret时留空不修改也能保存() {
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "sec");
        Map<String, String> in = new HashMap<>(Map.of(
                CaptchaService.MODE_KEY, "TURNSTILE", CaptchaService.TURNSTILE_SITE_KEY, "site"));
        assertThatCode(() -> service.validateConfigUpdate(in)).doesNotThrowAnyException();
    }

    /** 本次没动验证方式、但清空了 site key，而库里已是 Turnstile——同样要拦 */
    @Test
    void 已是Turnstile时清空SiteKey被拒() {
        mode(CaptchaMode.TURNSTILE);
        stored.put(CaptchaService.TURNSTILE_SITE_KEY, "site");
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "sec");
        Map<String, String> in = new HashMap<>(Map.of(CaptchaService.TURNSTILE_SITE_KEY, ""));
        assertThatThrownBy(() -> service.validateConfigUpdate(in)).satisfies(e -> assertThat(code(e)).isEqualTo(400));
    }

    // ---------- 配置页「测试 Turnstile」 ----------

    @Test
    void 测试时优先用表单里刚填的Secret() {
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "old");
        when(turnstileVerifier.verify("new", "tok", "1.2.3.4")).thenReturn(pass());

        assertThat(service.testTurnstile("tok", " new ", "1.2.3.4").success()).isTrue();
    }

    /** secret 只写不读，表单里通常是空的：此时必须回落到已保存的那份，否则「已保存的配置对不对」根本测不了 */
    @Test
    void 测试时表单Secret留空则用已保存的() {
        stored.put(CaptchaService.TURNSTILE_SECRET_KEY, "saved");
        when(turnstileVerifier.verify("saved", "tok", null)).thenReturn(pass());

        assertThat(service.testTurnstile("tok", "", null).success()).isTrue();
    }

    /** 测试不能把「不通过」变成异常吞掉：error-codes 要原样带回给管理员排查 */
    @Test
    void 测试不通过时原样带回错误码() {
        when(turnstileVerifier.verify(any(), any(), any())).thenReturn(reject("invalid-input-secret"));

        TurnstileVerifier.Result r = service.testTurnstile("tok", "bad", null);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCodes()).containsExactly("invalid-input-secret");
    }

    @Test
    void 测试时没有任何Secret或令牌被拒() {
        assertThatThrownBy(() -> service.testTurnstile("tok", "", null)).satisfies(e -> assertThat(code(e)).isEqualTo(400));
        assertThatThrownBy(() -> service.testTurnstile(" ", "sec", null)).satisfies(e -> assertThat(code(e)).isEqualTo(400));
        verifyNoInteractions(turnstileVerifier);
    }

    @Test
    void 测试时Cloudflare不可达给出明确提示() {
        when(turnstileVerifier.verify(any(), any(), any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> service.testTurnstile("tok", "sec", null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(503))
                .hasMessageContaining("challenges.cloudflare.com");
    }
}
