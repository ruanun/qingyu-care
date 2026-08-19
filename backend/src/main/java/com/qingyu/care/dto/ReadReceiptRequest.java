package com.qingyu.care.dto;

import lombok.Data;

/**
 * 已读回执上报请求。坐席端与访客端共用。
 *
 * <p>⚠️ <b>字段不写初始化器</b>：本仓库既有约定（{@code UpdateRequestNoInitializerTest} 守着
 * {@code *UpdateRequest}，本类虽不在其扫描范围内，约定同样适用）——Jackson 走无参构造会应用
 * 初始化器，让「请求体没给这个字段」与「给了默认值」无法区分。
 *
 * @author ruan
 */
@Data
public class ReadReceiptRequest {
    private Long upToMessageId;
}
