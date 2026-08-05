package com.qingyu.care.service.media;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
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

    /**
     * 造一张 IHDR 声明超大画布、但实际 IDAT 仍是 1x1 真实数据的 PNG——只用来验证「像素上限
     * 校验发生在解码像素之前」。若实现退化成先 {@code ImageIO.read} 解码再判断像素数，这个
     * 测试会因为要真的分配 fakeWidth×fakeHeight 的内存而变慢甚至 OOM，能反向暴露回归。
     */
    private static byte[] pngWithSpoofedDimensions(int fakeWidth, int fakeHeight) throws Exception {
        byte[] png = pngBytes(1, 1).clone();
        writeIntBE(png, 16, fakeWidth);
        writeIntBE(png, 20, fakeHeight);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17); // "IHDR"(4) + 宽高等 13 字节数据，共 17 字节参与 CRC
        writeIntBE(png, 29, (int) crc.getValue());
        return png;
    }

    private static void writeIntBE(byte[] arr, int offset, int value) {
        arr[offset] = (byte) (value >>> 24);
        arr[offset + 1] = (byte) (value >>> 16);
        arr[offset + 2] = (byte) (value >>> 8);
        arr[offset + 3] = (byte) value;
    }

    @Test
    void 合法PNG通过并读出宽高() throws Exception {
        // 大写扩展名：手机相机文件名普遍是 IMG_2024.JPG 这类，扩展名匹配必须大小写不敏感
        AttachmentValidator.ValidatedFile r = validator.validate(pngBytes(800, 600), "截图.PNG");

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
    void 可执行文件改名成pdf被拒() {
        // 与上面图片分支对称：文件分支的 MIME 比对同样不能被绕过，否则这道防线只守了一半
        byte[] exe = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> validator.validate(exe, "report.pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件内容与扩展名不符");
    }

    @Test
    void 图片声明像素超限被拒且不解码像素() throws Exception {
        // 15000x15000 = 2.25 亿像素，远超 8000 万上限；文件本身仍是 1x1 真实数据，几十字节
        byte[] bomb = pngWithSpoofedDimensions(15000, 15000);

        assertThatThrownBy(() -> validator.validate(bomb, "bomb.png"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图片尺寸过大");
    }

    @Test
    void WEBP通过校验但因JDK无解码器宽高恒为空() {
        // JDK 17 内置 ImageIO 没有 webp reader；Tika 仅靠 RIFF/WEBP 魔数即可探测，不需要完整文件
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

        AttachmentValidator.ValidatedFile r = validator.validate(webp, "pic.webp");

        assertThat(r.contentType()).isEqualTo("image/webp");
        assertThat(r.image()).isTrue();
        assertThat(r.width()).isNull();
        assertThat(r.height()).isNull();
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
    void 文件名为null被拒() {
        // MultipartFile#getOriginalFilename() 按 Spring 契约允许返回 null，不能让它变成 NPE 逃出 validate
        assertThatThrownBy(() -> validator.validate("hello".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 压缩文件6MB通过() {
        byte[] zip = new byte[6 * 1024 * 1024];
        zip[0] = 0x50; // "PK\x03\x04" 是 zip 的魔数
        zip[1] = 0x4B;
        zip[2] = 0x03;
        zip[3] = 0x04;

        AttachmentValidator.ValidatedFile r = validator.validate(zip, "archive.zip");

        assertThat(r.image()).isFalse();
    }

    @Test
    void 压缩文件21MB被拒() {
        byte[] zip = new byte[21 * 1024 * 1024];
        zip[0] = 0x50;
        zip[1] = 0x4B;
        zip[2] = 0x03;
        zip[3] = 0x04;

        assertThatThrownBy(() -> validator.validate(zip, "archive.zip"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件不能超过 20MB");
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
