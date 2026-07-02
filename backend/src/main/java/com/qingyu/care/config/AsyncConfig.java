package com.qingyu.care.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步配置
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    // 使用默认的异步任务执行器
    // 可以根据需要自定义 ThreadPoolTaskExecutor
}
