package com.qingyu.care;

import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻语客服 - AI智能客服系统启动类
 * 
 * @author QingYu Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableFileStorage  // 启用 X File Storage
@EnableScheduling   // 启用定时任务
@EnableAsync        // 启用异步处理
public class QingyuCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(QingyuCareApplication.class, args);
    }
}

