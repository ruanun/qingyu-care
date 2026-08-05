package com.qingyu.care.entity;

import com.qingyu.care.enums.MessageRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author ruan
 */
class ChatAttachmentTest {

    @Test
    void builder_构造出的实体字段齐全且继承租户基类() {
        ChatAttachment a = ChatAttachment.builder()
                .sessionId("s-1")
                .fileName("截图.png")
                .fileSize(1024L)
                .contentType("image/png")
                .storageKey("knowledge/chat/2026/08/05/ab12_截图.png")
                .width(800)
                .height(600)
                .uploaderRole(MessageRole.USER)
                .build();

        assertThat(a).isInstanceOf(BaseTenantEntity.class);
        assertThat(a.getMessageId()).isNull();   // 上传时未关联消息
        assertThat(a.getFileName()).isEqualTo("截图.png");
        assertThat(a.getWidth()).isEqualTo(800);
        assertThat(a.getUploaderRole()).isEqualTo(MessageRole.USER);
    }
}
