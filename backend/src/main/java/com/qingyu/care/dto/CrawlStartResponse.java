package com.qingyu.care.dto;

/**
 * 启动爬取的响应：站点 id + 用于轮询进度的任务 id。
 *
 * @author ruan
 */
public record CrawlStartResponse(Long siteId, String jobId) {
}
