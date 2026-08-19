package com.qingyu.care.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 已读回执上报请求。坐席端 {@code POST /v1/chat/session/{id}/read} 与访客端
 * {@code POST /open/v1/sessions/{id}/read}（见 Task 4）共用同一个 DTO——改这里两个端点都会生效。
 *
 * <p>⚠️ <b>字段不写初始化器</b>：本仓库既有约定（{@code UpdateRequestNoInitializerTest} 守着
 * {@code *UpdateRequest}，本类虽不在其扫描范围内，约定同样适用）——Jackson 走无参构造会应用
 * 初始化器，让「请求体没给这个字段」与「给了默认值」无法区分。
 *
 * <p>⚠️ <b>{@code @NotNull} 必须配合控制器方法参数上的 {@code @Valid} 才会生效</b>——漏加
 * {@code @Valid}，本约束会静默不校验。{@code upToMessageId} 传 null 若放过校验直接进
 * service：{@code ReadReceiptService.doMarkRead} 首次回执会先撞实体列的
 * {@code nullable = false} 抛 {@code DataIntegrityViolationException}，重试 UPDATE 仍 0 行，
 * 最终 {@code currentOrDefault} 兜底值也是 null——而 {@code markAgentRead} 返回类型是原始
 * {@code long}，拆箱 null 直接 NPE，对客户端表现为 500 而非语义正确的 400。
 *
 * @author ruan
 */
@Data
public class ReadReceiptRequest {

    @NotNull(message = "已读到的消息 id 不能为空")
    private Long upToMessageId;
}
