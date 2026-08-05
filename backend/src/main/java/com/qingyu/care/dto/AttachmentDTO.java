package com.qingyu.care.dto;

import com.qingyu.care.entity.ChatAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附件出参。{@code url} 由后端按调用来源拼装（管理端 /v1/chat/files/、Widget /open/v1/chat/files/），
 * 不存库——存了一个某一端用不了的地址只会误导。
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
    /** 访问地址，形如 /api/v1/chat/files/12 */
    private String url;

    public static AttachmentDTO of(ChatAttachment a, String urlPrefix) {
        return AttachmentDTO.builder()
                .id(a.getId())
                .fileName(a.getFileName())
                .contentType(a.getContentType())
                .fileSize(a.getFileSize())
                .width(a.getWidth())
                .height(a.getHeight())
                .url(urlPrefix + a.getId())
                .build();
    }
}
