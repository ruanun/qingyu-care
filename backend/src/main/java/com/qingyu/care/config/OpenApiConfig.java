package com.qingyu.care.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 文档配置
 */
@Configuration
public class OpenApiConfig {
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("轻语客服 API")
                        .version("1.0.0")
                        .description("轻语客服（QingYu Care）- AI智能客服系统 RESTful API 文档")
                        .contact(new Contact()
                                .name("QingYu Team")
                                .email("support@qingyu.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080/api").description("开发环境"),
                        new Server().url("https://api.qingyu.com").description("生产环境")
                ));
    }
}
