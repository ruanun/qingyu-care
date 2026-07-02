package com.qingyu.care.config;

import cn.hutool.core.util.StrUtil;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileStorageProperties.AmazonS3Config;
import org.dromara.x.file.storage.core.platform.FileStorageClientFactory;

/**
 * 自定义 Amazon S3 客户端工厂
 * 启用 Path Style Access 以兼容 RustFS/MinIO 等 S3 兼容存储
 */
@Slf4j
@Getter
@Setter
@NoArgsConstructor
public class PathStyleAmazonS3ClientFactory implements FileStorageClientFactory<AmazonS3> {
    private String platform;
    private String accessKey;
    private String secretKey;
    private String region;
    private String endPoint;
    private volatile AmazonS3 client;

    public PathStyleAmazonS3ClientFactory(AmazonS3Config config) {
        platform = config.getPlatform();
        accessKey = config.getAccessKey();
        secretKey = config.getSecretKey();
        region = config.getRegion();
        endPoint = config.getEndPoint();
    }

    @Override
    public AmazonS3 getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    log.info("创建 S3 客户端, endpoint: {}, region: {}", endPoint, region);
                    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
                            .withCredentials(
                                    new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                            // 关键：启用 Path Style Access
                            .withPathStyleAccessEnabled(true);
                    
                    if (StrUtil.isNotBlank(endPoint)) {
                        builder.withEndpointConfiguration(
                                new AwsClientBuilder.EndpointConfiguration(endPoint, region));
                    } else if (StrUtil.isNotBlank(region)) {
                        builder.withRegion(region);
                    }
                    client = builder.build();
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
}
