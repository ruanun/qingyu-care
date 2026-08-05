package com.qingyu.care.controller.support;

import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.service.FileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 附件下载响应的组装。两条访问端点（管理端 / 开放 API）鉴权不同但响应形态一致，共用这里。
 *
 * @author ruan
 */
public final class AttachmentResponses {

    private AttachmentResponses() {
    }

    public static ResponseEntity<StreamingResponseBody> stream(ChatAttachment a, FileService fileService) {
        boolean inline = a.getContentType() != null && a.getContentType().startsWith("image/");
        // 文件名可能含中文与特殊字符，用 RFC 5987 编码传真实文件名；
        // 同时按 RFC 6266 给一个 ASCII 兜底 filename=（旧客户端/不支持 filename* 的场景）
        String encoded = URLEncoder.encode(a.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = (inline ? "inline" : "attachment")
                + "; filename=\"" + asciiFallback(a.getFileName()) + "\""
                + "; filename*=UTF-8''" + encoded;

        StreamingResponseBody body = out -> {
            try (InputStream in = fileService.openStream(a.getStorageKey())) {
                in.transferTo(out);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                // 与「白名单不收 SVG」是同一防御目标的两半：没有它，浏览器可能对声明为
                // text/plain 的内容做 MIME 嗅探并当 HTML 执行，而附件是从我们自己的域下发的。
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(body);
    }

    /**
     * quoted-string 里的 ASCII 兜底名：非 ASCII/控制字符统一替换为 {@code _}，
     * 并把会破坏 quoted-string 语法的引号、反斜杠也替换掉（兜底名允许有损，
     * 真实文件名走上面的 {@code filename*}，这里只求不破坏响应头语法）。
     */
    private static String asciiFallback(String fileName) {
        return fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_").replace("\\", "_");
    }
}
