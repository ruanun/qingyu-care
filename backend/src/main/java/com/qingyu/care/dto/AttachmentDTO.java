package com.qingyu.care.dto;

import com.qingyu.care.entity.ChatAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附件出参。⚠️ 不提供拼好的 {@code url} 字段——两条下载端点
 * （{@code /v1/chat/files/{id}}、{@code /open/v1/chat/files/{id}}）都是请求头鉴权
 * （管理端 satoken，开放 API X-Api-Key 或 Origin），而浏览器原生的 {@code <img src>}/
 * {@code <a href>} 不会带自定义头；即便按 id 拼出正确路径也渲染不出来，只会把两端引到坑里。
 * 正确用法是客户端用自己的 apiBase + 鉴权头 {@code fetch} 该端点拿到二进制后
 * {@code URL.createObjectURL} 转成 blob URL 再渲染，故这里只回 {@code id} 供客户端自行拼接。
 *
 * @author ruan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentDTO {

    private Long id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private Integer width;
    private Integer height;

    public static AttachmentDTO of(ChatAttachment a) {
        return AttachmentDTO.builder()
                .id(a.getId())
                .fileName(a.getFileName())
                .contentType(a.getContentType())
                .fileSize(a.getFileSize())
                .width(a.getWidth())
                .height(a.getHeight())
                .build();
    }
}
