package com.qingyu.care.enums;

/**
 * 文档可见性级别（同时用作调用方访问级别：PUBLIC=只见公开文档，INTERNAL=全见）。
 *
 * @author ruan
 */
public enum DocumentVisibility {
    /** 公开：对所有渠道（含 Widget 匿名访客）可见 */
    PUBLIC,
    /** 内部：仅受信调用（服务端 API Key / 管理后台登录成员）可见 */
    INTERNAL
}
