package com.qingyu.care.config;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.platform.FileStorageClientFactory;
import org.dromara.x.file.storage.spring.SpringFileStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * X-File-Storage 自定义 Client 工厂配置
 * 启用 Path Style Access 以兼容 RustFS/MinIO 等 S3 兼容存储
 */
@Slf4j
@Configuration
public class FileStorageConfig {

    @Value("${dromara.x-file-storage.amazon-s3[0].access-key}")
    private String accessKey;
    
    @Value("${dromara.x-file-storage.amazon-s3[0].secret-key}")
    private String secretKey;
    
    @Value("${dromara.x-file-storage.amazon-s3[0].end-point}")
    private String endPoint;
    
    @Value("${dromara.x-file-storage.amazon-s3[0].region}")
    private String region;

    /**
     * 创建 AmazonS3 客户端 Bean，供 FileService 使用
     */
    @Bean
    public AmazonS3 amazonS3Client() {
        log.info("创建 AmazonS3 客户端 Bean (Path Style Access 已启用)");
        log.info("  endpoint: {}", endPoint);
        log.info("  region: {}", region);
        
        return AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(accessKey, secretKey)))
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(endPoint, region))
                .withPathStyleAccessEnabled(true)
                .build();
    }

    /**
     * 自定义 Amazon S3 Client 工厂，启用 Path Style Access
     * 注意：返回值必须是 List<List<FileStorageClientFactory<?>>> 类型
     */
    @Bean
    public List<List<FileStorageClientFactory<?>>> myAmazonS3FileStorageClientFactoryList(SpringFileStorageProperties properties) {
        List<FileStorageClientFactory<?>> factoryList = properties.getAmazonS3().stream()
                .filter(config -> config.getEnableStorage() != null && config.getEnableStorage())
                .map(config -> (FileStorageClientFactory<?>) new FileStorageClientFactory<AmazonS3>() {
                    private volatile AmazonS3 client;

                    @Override
                    public String getPlatform() {
                        return config.getPlatform();
                    }

                    @Override
                    public AmazonS3 getClient() {
                        if (client == null) {
                            synchronized (this) {
                                if (client == null) {
                                    log.info("创建 S3 客户端 (Path Style Access 已启用)");
                                    log.info("  platform: {}", config.getPlatform());
                                    log.info("  endpoint: {}", config.getEndPoint());
                                    log.info("  region: {}", config.getRegion());
                                    log.info("  bucket: {}", config.getBucketName());
                                    
                                    client = AmazonS3ClientBuilder.standard()
                                            .withCredentials(new AWSStaticCredentialsProvider(
                                                    new BasicAWSCredentials(config.getAccessKey(), config.getSecretKey())))
                                            .withEndpointConfiguration(
                                                    new AwsClientBuilder.EndpointConfiguration(
                                                            config.getEndPoint(), config.getRegion()))
                                            // 关键：启用 Path Style Access
                                            .withPathStyleAccessEnabled(true)
                                            .build();
                                    
                                    log.info("S3 客户端创建成功");
                                }
                            }
                        }
                        return client;
                    }

                    @Override
                    public void close() {
                        if (client != null) {
                            client.shutdown();
                            client = null;
                        }
                    }
                })
                .collect(Collectors.toList());
        
        if (!factoryList.isEmpty()) {
            log.info("注册了 {} 个自定义 S3 Client 工厂 (Path Style Access)", factoryList.size());
        }
        
        return Collections.singletonList(factoryList);
    }
}

