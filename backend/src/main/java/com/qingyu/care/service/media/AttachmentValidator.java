package com.qingyu.care.service.media;

import com.qingyu.care.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 附件校验：大小、扩展名、真实类型、图片尺寸。不碰数据库、不碰存储，纯可测。
 *
 * <p>四道校验顺序不可换：先按类型分别限额 → 扩展名白名单 → Tika 探测真实类型并与扩展名比对
 * → 图片再读尺寸。第三道是关键——只信客户端传的 Content-Type 等于没校验，把 .exe 改名成
 * .jpg 是最基本的绕过手法。
 *
 * <p>SVG 不在白名单里：它是 XSS 载体，浏览器会执行其中的 script，而附件是从本域下发的。
 *
 * @author ruan
 */
@Component
@Slf4j
public class AttachmentValidator {

    /** 图片上限 5MB */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    /** 其它文件上限 20MB */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** 扩展名 → 期望的真实 MIME。故意不含 svg */
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "gif", "image/gif");

    private static final Map<String, Set<String>> FILE_TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "doc", Set.of("application/msword"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "xls", Set.of("application/vnd.ms-excel"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "txt", Set.of("text/plain"),
            "zip", Set.of("application/zip", "application/x-zip-compressed"));

    private final Tika tika = new Tika();

    public record ValidatedFile(String contentType, boolean image, Integer width, Integer height) {}

    public ValidatedFile validate(byte[] bytes, String originalFilename) {
        String ext = extensionOf(originalFilename);
        boolean isImage = IMAGE_TYPES.containsKey(ext);
        boolean isFile = FILE_TYPES.containsKey(ext);

        if (!isImage && !isFile) {
            throw new BusinessException("不支持的文件类型：" + (ext.isEmpty() ? "（无扩展名）" : ext));
        }

        // 先卡大小：按扩展名归类即可，此刻还没探测真实类型；探测后若类别翻转会在下面再卡一次
        long limit = isImage ? MAX_IMAGE_BYTES : MAX_FILE_BYTES;
        requireWithinLimit(bytes.length, limit, isImage);

        String detected = tika.detect(bytes);

        if (isImage) {
            if (!IMAGE_TYPES.get(ext).equals(detected)) {
                throw new BusinessException("文件内容与扩展名不符");
            }
            Integer[] size = readImageSize(bytes);
            return new ValidatedFile(detected, true, size[0], size[1]);
        }

        if (!FILE_TYPES.get(ext).contains(detected)) {
            throw new BusinessException("文件内容与扩展名不符");
        }
        // 探测出来是图片却用了文件扩展名：按图片限额重卡一次，防止拿 .txt 扩展名绕过 5MB
        if (detected.startsWith("image/")) {
            requireWithinLimit(bytes.length, MAX_IMAGE_BYTES, true);
        }
        return new ValidatedFile(detected, false, null, null);
    }

    private void requireWithinLimit(long actual, long limit, boolean image) {
        if (actual > limit) {
            throw new BusinessException(
                    (image ? "图片不能超过 " : "文件不能超过 ") + (limit / 1024 / 1024) + "MB");
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 读不出尺寸不算失败——宽高只用于前端预留占位，缺了顶多布局跳一下 */
    private Integer[] readImageSize(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                return new Integer[]{img.getWidth(), img.getHeight()};
            }
        } catch (Exception e) {
            log.warn("读取图片尺寸失败，跳过宽高: {}", e.getMessage());
        }
        return new Integer[]{null, null};
    }
}
