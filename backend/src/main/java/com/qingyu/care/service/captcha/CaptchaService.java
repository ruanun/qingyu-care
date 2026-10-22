package com.qingyu.care.service.captcha;

import com.qingyu.care.enums.CaptchaMode;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 注册入口的人机验证：平台管理员在「平台配置」里选 不启用 / 图形验证码 / Cloudflare Turnstile。
 *
 * <p>⚠️ 两种验证都 <b>fail-closed</b>（Redis 或 Cloudflare 不可达时拒绝注册），与限流的 fail-open 口径相反：
 * 限流挡的是「量」，放行一时无妨；验证码挡的是「人机」，一放行就等于没开。注册不是关键路径，暂时不可用可以接受。
 *
 * <p>图形验证码的答案存 Redis、取出即删（GETDEL）：答错也作废，同一个 captchaId 不能反复猜。
 *
 * @author ruan
 */
@Slf4j
@Service
public class CaptchaService {

    public static final String MODE_KEY = "platform.registration.captcha";
    public static final String TURNSTILE_SITE_KEY = "platform.captcha.turnstile.site-key";
    public static final String TURNSTILE_SECRET_KEY = "platform.captcha.turnstile.secret-key";

    static final String REDIS_KEY_PREFIX = "auth:captcha:";
    static final Duration TTL = Duration.ofMinutes(5);
    /** 去掉 0/O、1/I/l 这类肉眼难辨的字符 */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    private final ConfigService configService;
    private final TurnstileVerifier turnstileVerifier;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(ConfigService configService, TurnstileVerifier turnstileVerifier,
                          ObjectProvider<StringRedisTemplate> redisProvider) {
        this.configService = configService;
        this.turnstileVerifier = turnstileVerifier;
        this.redisProvider = redisProvider;
    }

    public record ImageCaptcha(String captchaId, String image) {
    }

    /** 当前生效的验证方式；库里是非法值（只可能是绕过配置接口直接改库）时按图形验证码处理，宁严勿松 */
    public CaptchaMode currentMode() {
        String raw = configService.get(OwnerScope.PLATFORM, null, MODE_KEY, CaptchaMode.NONE.name());
        try {
            return CaptchaMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("平台配置 {} 取值非法: {}，按 IMAGE 处理", MODE_KEY, raw);
            return CaptchaMode.IMAGE;
        }
    }

    /**
     * 平台配置保存前的校验（会就地把验证方式规范成大写枚举名）。
     *
     * <p>⚠️ 选了 Turnstile 却没配齐 site key / secret key 时必须在这里拒绝：放过去的后果是注册页渲染不出组件、
     * 或每次校验都被 Cloudflare 判为无效——注册入口整个不可用，而保存时没有任何提示。
     *
     * @param incoming 本次要写入的配置（凭据留空表示不修改，调用方已剔除）
     */
    public void validateConfigUpdate(Map<String, String> incoming) {
        String rawMode = incoming.get(MODE_KEY);
        CaptchaMode mode;
        if (rawMode != null && !rawMode.isBlank()) {
            try {
                mode = CaptchaMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "注册验证方式取值非法：" + rawMode);
            }
            incoming.put(MODE_KEY, mode.name());
        } else {
            mode = currentMode();
        }
        if (mode != CaptchaMode.TURNSTILE) {
            return;
        }
        String siteKey = incoming.containsKey(TURNSTILE_SITE_KEY) ? incoming.get(TURNSTILE_SITE_KEY) : turnstileSiteKey();
        String secret = incoming.containsKey(TURNSTILE_SECRET_KEY)
                ? incoming.get(TURNSTILE_SECRET_KEY)
                : configService.get(OwnerScope.PLATFORM, null, TURNSTILE_SECRET_KEY, "");
        if (isBlank(siteKey) || isBlank(secret)) {
            throw new BusinessException(400, "启用 Cloudflare Turnstile 需先填写 Site Key 与 Secret Key");
        }
    }

    public String turnstileSiteKey() {
        return configService.get(OwnerScope.PLATFORM, null, TURNSTILE_SITE_KEY, "");
    }

    /**
     * 平台管理员在配置页「测试 Turnstile」：用表单里刚填的 secret（留空则用已保存的）校验一枚真实令牌，
     * 把 Cloudflare 的判定原样带回，而不是只给「通过/不通过」——配错时 error-codes 才是排查依据。
     *
     * @param secretKey 表单当前填写的 secret；空白表示用已保存的
     */
    public TurnstileVerifier.Result testTurnstile(String token, String secretKey, String clientIp) {
        if (isBlank(token)) {
            throw new BusinessException(400, "请先完成人机验证");
        }
        String secret = isBlank(secretKey)
                ? configService.get(OwnerScope.PLATFORM, null, TURNSTILE_SECRET_KEY, "")
                : secretKey.trim();
        if (secret.isBlank()) {
            throw new BusinessException(400, "请先填写 Turnstile Secret Key");
        }
        try {
            return turnstileVerifier.verify(secret, token, clientIp);
        } catch (IllegalStateException e) {
            log.warn("{}", e.getMessage());
            throw new BusinessException(503, "无法连接 Cloudflare 校验服务（challenges.cloudflare.com），请检查服务器出站网络");
        }
    }

    /** 生成一张图形验证码。仅在当前模式为 IMAGE 时可用，免得这个免登录端点在不需要时也能被拿来刷 */
    public ImageCaptcha generateImage() {
        if (currentMode() != CaptchaMode.IMAGE) {
            throw new BusinessException(400, "当前未启用图形验证码");
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String code = sb.toString();
        String id = UUID.randomUUID().toString().replace("-", "");
        StringRedisTemplate redis = requireRedis();
        try {
            redis.opsForValue().set(REDIS_KEY_PREFIX + id, code, TTL);
        } catch (Exception e) {
            log.warn("图形验证码写入 Redis 失败: {}", e.getMessage());
            throw unavailable();
        }
        return new ImageCaptcha(id, "data:image/png;base64," + Base64.getEncoder().encodeToString(renderPng(code)));
    }

    /**
     * 按当前模式校验，不通过抛 {@link BusinessException}。
     *
     * @param captchaId      图形验证码 id（IMAGE 模式）
     * @param captchaCode    用户输入的图形验证码（IMAGE 模式，大小写不敏感）
     * @param turnstileToken Turnstile 组件回调给出的令牌（TURNSTILE 模式）
     */
    public void verify(String captchaId, String captchaCode, String turnstileToken, String clientIp) {
        switch (currentMode()) {
            case NONE -> {
            }
            case IMAGE -> verifyImage(captchaId, captchaCode);
            case TURNSTILE -> verifyTurnstile(turnstileToken, clientIp);
        }
    }

    private void verifyImage(String captchaId, String captchaCode) {
        if (isBlank(captchaId) || isBlank(captchaCode)) {
            throw new BusinessException(400, "请输入验证码");
        }
        StringRedisTemplate redis = requireRedis();
        String expected;
        try {
            expected = redis.opsForValue().getAndDelete(REDIS_KEY_PREFIX + captchaId);
        } catch (Exception e) {
            log.warn("图形验证码读取 Redis 失败: {}", e.getMessage());
            throw unavailable();
        }
        if (expected == null || !expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
    }

    private void verifyTurnstile(String token, String clientIp) {
        if (isBlank(token)) {
            throw new BusinessException(400, "请先完成人机验证");
        }
        String secret = configService.get(OwnerScope.PLATFORM, null, TURNSTILE_SECRET_KEY, "");
        if (secret.isBlank()) {
            log.warn("注册验证方式为 TURNSTILE 但未配置 Secret Key，注册将一律失败");
            throw unavailable();
        }
        boolean ok;
        try {
            ok = turnstileVerifier.verify(secret, token, clientIp).success();
        } catch (IllegalStateException e) {
            log.warn("{}", e.getMessage());
            throw unavailable();
        }
        if (!ok) {
            throw new BusinessException(400, "人机验证未通过，请重试");
        }
    }

    private StringRedisTemplate requireRedis() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            throw unavailable();
        }
        return redis;
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "验证服务暂时不可用，请稍后再试");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 画验证码图：浅色底 + 逐字随机旋转/颜色 + 干扰线与噪点 */
    byte[] renderPng(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(243, 246, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 6; i++) {
                g.setColor(randomColor(150, 210));
                g.setStroke(new BasicStroke(1f + random.nextFloat()));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            int step = WIDTH / (LENGTH + 1);
            for (int i = 0; i < LENGTH; i++) {
                AffineTransform saved = g.getTransform();
                int x = step / 2 + i * step + random.nextInt(6);
                int y = 29 + random.nextInt(5) - 2;
                g.rotate(Math.toRadians(random.nextInt(51) - 25), x + 8, y - 10);
                g.setColor(randomColor(20, 110));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(saved);
            }

            for (int i = 0; i < 60; i++) {
                image.setRGB(random.nextInt(WIDTH), random.nextInt(HEIGHT), randomColor(100, 220).getRGB());
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片编码失败", e);
        }
    }

    private Color randomColor(int min, int max) {
        int span = max - min;
        return new Color(min + random.nextInt(span), min + random.nextInt(span), min + random.nextInt(span));
    }
}
