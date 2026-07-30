package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.platform.AmazonS3V2FileStorageClientFactory.AmazonS3V2Client;
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
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
     *
     * <p><b>泛型实参必须是 {@link AmazonS3V2Client} 而非裸 {@code S3Client}</b>：
     * {@code AmazonS3V2FileStorage.getClient()} 对工厂产物做的是无条件 checkcast
     * （字节码 {@code checkcast AmazonS3V2Client}），返回裸 {@code S3Client} 会让每次上传都抛
     * {@code DefaultS3Client cannot be cast to AmazonS3V2Client}。该 wrapper 同时承载
     * client 与 presigner，故两者都要预置为 path-style——wrapper 自身惰性构建时只设
     * endpointOverride、不设 forcePathStyle，留空会退回 virtual-hosted-style 打不通 RustFS。
     */
    @Bean
    public List<List<FileStorageClientFactory<?>>> myAmazonS3V2FileStorageClientFactoryList(SpringFileStorageProperties properties) {
        List<FileStorageClientFactory<?>> factoryList = properties.getAmazonS3V2().stream()
                .filter(config -> config.getEnableStorage() != null && config.getEnableStorage())
                .map(config -> (FileStorageClientFactory<?>) new FileStorageClientFactory<AmazonS3V2Client>() {
                    private volatile AmazonS3V2Client client;

                    @Override
                    public String getPlatform() {
                        return config.getPlatform();
                    }

                    @Override
                    public AmazonS3V2Client getClient() {
                        if (client == null) {
                            synchronized (this) {
                                if (client == null) {
                                    log.info("创建 S3 客户端 (Path Style Access 已启用)");
                                    log.info("  platform: {}", config.getPlatform());
                                    log.info("  endpoint: {}", config.getEndPoint());
                                    log.info("  region: {}", config.getRegion());
                                    log.info("  bucket: {}", config.getBucketName());
                                    AmazonS3V2Client wrapper = new AmazonS3V2Client(config.getAccessKey(),
                                            config.getSecretKey(), config.getRegion(), config.getEndPoint());
                                    // 预置两者，杜绝 wrapper 走自身惰性构建（那条路径不带 forcePathStyle）
                                    wrapper.setClient(buildPathStyleClient(config.getAccessKey(), config.getSecretKey(),
                                            config.getRegion(), config.getEndPoint()));
                                    wrapper.setPresigner(buildPathStylePresigner(config.getAccessKey(), config.getSecretKey(),
                                            config.getRegion(), config.getEndPoint()));
                                    client = wrapper;
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

    /** 构建启用 path-style 的预签名器（SDK v2）；x-file-storage 生成预签名 URL 时用它。 */
    private static S3Presigner buildPathStylePresigner(String accessKey, String secretKey, String region, String endPoint) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (endPoint != null && !endPoint.isBlank()) {
            builder.endpointOverride(URI.create(endPoint));
        }
        return builder.build();
    }
}
