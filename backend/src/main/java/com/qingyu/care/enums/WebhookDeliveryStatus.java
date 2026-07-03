package com.qingyu.care.enums;

/** Webhook 投递状态。 */
public enum WebhookDeliveryStatus {
    /** 待投递（含待重试）。 */
    PENDING,
    /** 已被某线程认领、投递中。 */
    PROCESSING,
    /** 投递成功（2xx）。 */
    SUCCESS,
    /** 重试耗尽仍失败。 */
    FAILED
}
