package com.qingyu.care.service.media;

import com.qingyu.care.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
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
 * <p>图片尺寸读取只解析文件头（{@link ImageReader#getWidth}/{@link ImageReader#getHeight}），
 * 绝不用 {@code ImageIO.read} 完整解码像素——字节数限额只约束文件体积，一张几百 KB 但 IHDR
 * 声明超大画布的 PNG（解压炸弹）解码后可轻松吃光几百 MB 堆内存，放大比可达千倍。故额外对
 * 「宽 × 高」设像素上限，在真正解码之前就拒绝。
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
    /** 图片像素上限 8000 万——手机截图 4000×3000 才 1200 万，够用有余；防解压炸弹 */
    private static final long MAX_IMAGE_PIXELS = 80_000_000L;

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

        // 按扩展名归类即可确定限额：下面会校验「真实 MIME 必须等于扩展名对应值」，
        // FILE_TYPES 的值集合里没有任何一项以 image/ 开头，类别因此不可能翻转——
        // 按扩展名限额与按真实类型限额是等价的，无需在探测后再卡一次。
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

    /**
     * 读不出尺寸不算失败——宽高只用于前端预留占位，缺了顶多布局跳一下。
     *
     * <p>只经 {@link ImageReader} 解析文件头取宽高，不调用 {@code reader.read(0)} 解码像素，
     * 从源头避免解压炸弹（详见类注释）。JDK 内置 ImageIO 没有 webp 解码器，此时
     * {@code getImageReaders} 拿不到任何 reader、只能放弃——webp 宽高恒为空是已知限制，
     * 不是本方法的 bug。
     */
    private Integer[] readImageSize(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                log.warn("无法为图片字节创建 ImageInputStream，跳过宽高读取");
                return new Integer[]{null, null};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                log.warn("未找到该图片格式对应的解码器（如 JDK 内置 ImageIO 不支持 webp），跳过宽高读取");
                return new Integer[]{null, null};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_IMAGE_PIXELS) {
                    throw new BusinessException("图片尺寸过大（" + width + "x" + height + "），超过 8000 万像素上限");
                }
                return new Integer[]{width, height};
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取图片尺寸失败（解码异常），跳过宽高: {}", e.getMessage());
            return new Integer[]{null, null};
        }
    }
}
