package com.qingyu.care.service.media;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author ruan
 */
class AttachmentValidatorTest {

    private final AttachmentValidator validator = new AttachmentValidator();

    /** 造一张真实的 PNG，让 Tika 能探测出 image/png、ImageIO 能读出尺寸 */
    private static byte[] pngBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void 合法PNG通过并读出宽高() throws Exception {
        AttachmentValidator.ValidatedFile r = validator.validate(pngBytes(800, 600), "截图.png");

        assertThat(r.contentType()).isEqualTo("image/png");
        assertThat(r.image()).isTrue();
        assertThat(r.width()).isEqualTo(800);
        assertThat(r.height()).isEqualTo(600);
    }

    @Test
    void 可执行文件改名成jpg被拒() {
        // MZ 是 Windows PE 可执行文件的魔数，Tika 会探测成 application/x-msdownload
        byte[] exe = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> validator.validate(exe, "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件内容与扩展名不符");
    }

    @Test
    void SVG被拒即便扩展名与内容一致() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(svg, "icon.svg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 图片超过5MB被拒() throws Exception {
        // 用一张大图撑过 5MB；随机噪点让 PNG 压不动
        BufferedImage img = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 2000; x++) {
            for (int y = 0; y < 2000; y++) {
                img.setRGB(x, y, (x * 7919 + y * 104729) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] big = out.toByteArray();
        assertThat(big.length).isGreaterThan(5 * 1024 * 1024);

        assertThatThrownBy(() -> validator.validate(big, "big.png"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图片不能超过");
    }

    @Test
    void 非白名单扩展名被拒() {
        byte[] sh = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(sh, "run.sh"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 无扩展名被拒() {
        assertThatThrownBy(() -> validator.validate("hello".getBytes(StandardCharsets.UTF_8), "noext"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 文本文件按文件类通过且不带宽高() {
        AttachmentValidator.ValidatedFile r =
                validator.validate("订单号 12345".getBytes(StandardCharsets.UTF_8), "备注.txt");

        assertThat(r.image()).isFalse();
        assertThat(r.width()).isNull();
        assertThat(r.height()).isNull();
    }
}
