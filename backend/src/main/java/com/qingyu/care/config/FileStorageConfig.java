package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.platform.FileStorageClientFactory;
import org.dromara.x.file.storage.spring.SpringFileStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * X-File-Storage 自定义 Client 工厂配置（AWS SDK v2）。
 * 启用 Path Style Access 以兼容 RustFS/MinIO 等 S3 兼容存储——
 * SDK v2 默认 virtual-hosted-style（bucket.endpoint），RustFS 必须 path-style（endpoint/bucket），
 * 且 x-file-storage 内置的 amazon-s3-v2 工厂不设 forcePathStyle，故必须自定义工厂覆盖。
 */
@Slf4j
@Configuration
public class FileStorageConfig {

    @Value("${dromara.x-file-storage.amazon-s3-v2[0].access-key}")
    private String accessKey;

    @Value("${dromara.x-file-storage.amazon-s3-v2[0].secret-key}")
    private String secretKey;

    @Value("${dromara.x-file-storage.amazon-s3-v2[0].end-point}")
    private String endPoint;

    @Value("${dromara.x-file-storage.amazon-s3-v2[0].region}")
    private String region;

    /**
     * 创建 S3Client Bean（Path Style Access 已启用），供 FileService 直接下载/删除/存在性检查使用。
     */
    @Bean
    public S3Client s3Client() {
        log.info("创建 S3Client Bean (Path Style Access 已启用)");
        log.info("  endpoint: {}", endPoint);
        log.info("  region: {}", region);
        return buildPathStyleClient(accessKey, secretKey, region, endPoint);
    }

    /**
     * 自定义 amazon-s3-v2 Client 工厂，启用 Path Style Access。
     * 注意：返回值必须是 List&lt;List&lt;FileStorageClientFactory&lt;?&gt;&gt;&gt; 类型。
     */
    @Bean
    public List<List<FileStorageClientFactory<?>>> myAmazonS3V2FileStorageClientFactoryList(SpringFileStorageProperties properties) {
        List<FileStorageClientFactory<?>> factoryList = properties.getAmazonS3V2().stream()
                .filter(config -> config.getEnableStorage() != null && config.getEnableStorage())
                .map(config -> (FileStorageClientFactory<?>) new FileStorageClientFactory<S3Client>() {
                    private volatile S3Client client;

                    @Override
                    public String getPlatform() {
                        return config.getPlatform();
                    }

                    @Override
                    public S3Client getClient() {
                        if (client == null) {
                            synchronized (this) {
                                if (client == null) {
                                    log.info("创建 S3 客户端 (Path Style Access 已启用)");
                                    log.info("  platform: {}", config.getPlatform());
                                    log.info("  endpoint: {}", config.getEndPoint());
                                    log.info("  region: {}", config.getRegion());
                                    log.info("  bucket: {}", config.getBucketName());
                                    client = buildPathStyleClient(config.getAccessKey(), config.getSecretKey(),
                                            config.getRegion(), config.getEndPoint());
                                    log.info("S3 客户端创建成功");
                                }
                            }
                        }
                        return client;
                    }

                    @Override
                    public void close() {
                        if (client != null) {
                            client.close();
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

    /** 构建启用 path-style 的同步 S3Client（SDK v2）。 */
    private static S3Client buildPathStyleClient(String accessKey, String secretKey, String region, String endPoint) {
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                // 关键：启用 Path Style Access（RustFS/MinIO 必需）
                .forcePathStyle(true);
        if (endPoint != null && !endPoint.isBlank()) {
            builder.endpointOverride(URI.create(endPoint));
        }
        return builder.build();
    }
}
