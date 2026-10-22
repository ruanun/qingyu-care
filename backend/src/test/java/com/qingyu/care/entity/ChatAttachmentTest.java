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

    /**
     * 守护 equals/hashCode 语义：ChatAttachment 必须按 id 判等，不能继承
     * BaseTenantEntity 的 @Data 生成版本（只比较 tenantId/appId）。
     *
     * <p>两个实例 id 不同、其余业务字段也不同，只有 tenantId/appId 恰好都是
     * null（多数附件在落租户上下文之前就是这个状态）——若类上漏了
     * {@code @EqualsAndHashCode(onlyExplicitlyIncluded = true)} + 字段上漏了
     * {@code @EqualsAndHashCode.Include}，这两个对象会被判定为"相等"，用在
     * Set/Map 去重或索引时会静默错误合并（MM-6 分组附件到消息时正好会用到）。
     */
    @Test
    void 不同id的附件即便tenantId和appId都为空也不相等() {
        ChatAttachment a = ChatAttachment.builder()
                .sessionId("s-1")
                .fileName("a.png")
                .fileSize(10L)
                .contentType("image/png")
                .storageKey("key-a")
                .uploaderRole(MessageRole.USER)
                .build();
        a.setId(1L);

        ChatAttachment b = ChatAttachment.builder()
                .sessionId("s-2")
                .fileName("b.png")
                .fileSize(20L)
                .contentType("image/jpeg")
                .storageKey("key-b")
                .uploaderRole(MessageRole.AGENT)
                .build();
        b.setId(2L);

        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }
}
