package com.qingyu.care.dto;

/**
 * Webhook 测试事件投递结果。
 *
 * @author ruan
 */
public record WebhookTestResult(boolean delivered, Integer responseStatus, String error) {
}
