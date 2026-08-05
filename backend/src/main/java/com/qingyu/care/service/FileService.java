package com.qingyu.care.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务
 * 封装 x-file-storage，提供知识库文件的上传、下载、删除功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileStorageService fileStorageService;
    private final S3Client s3Client;
    
    @Value("${dromara.x-file-storage.amazon-s3-v2[0].bucket-name}")
    private String bucketName;

    @Value("${dromara.x-file-storage.amazon-s3-v2[0].base-path:knowledge/}")
    private String basePath;

    /**
     * 上传知识库文件
     *
     * @param file 上传的文件
     * @return 文件信息（包含存储路径、URL等）
     */
    public FileInfo uploadKnowledgeFile(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String path = generateFilePath("documents");
        
        log.info("开始上传文件: {} -> {}", originalFilename, path);
        
        FileInfo fileInfo = fileStorageService.of(file)
                .setPath(path)
                .setSaveFilename(generateFileName(originalFilename))
                .upload();
        
        log.info("文件上传成功: {} -> {}", originalFilename, fileInfo.getUrl());
        return fileInfo;
    }

    /**
     * 上传聊天附件。与知识库文档分开放在 chat/ 前缀下（实际对象键带全局 base-path 前缀）。
     *
     * @return S3 对象键，供后续 openStream/deleteFile 使用
     */
    public String uploadChatBytes(byte[] bytes, String originalFilename, String contentType) {
        String path = generateFilePath("chat");
        String filename = generateFileName(originalFilename);

        FileInfo fileInfo = fileStorageService.of(bytes)
                .setPath(path)
                .setSaveFilename(filename)
                .setContentType(contentType)
                .upload();

        log.info("聊天附件上传成功: {} -> {}", originalFilename, fileInfo.getUrl());
        return fileInfo.getUrl();
    }

    /**
     * 流式读取对象。附件最大 20MB，用 byte[] 会让每个并发请求都吃满一份堆内存。
     */
    public InputStream openStream(String storageKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(storageKey)
                .build();
        return s3Client.getObject(request);
    }

    /**
     * 下载文件（直接使用 S3 客户端）
     *
     * @param filePath 文件存储路径（完整 S3 对象键）
     * @return 文件字节数组
     */
    public byte[] downloadFile(String filePath) {
        log.info("开始下载文件: {}", filePath);
        
        // filePath 已经是完整的 S3 对象键，不需要再加 basePath
        String objectKey = filePath;
        log.debug("S3 对象键: {}", objectKey);

        try {
            byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build()).asByteArray();
            log.info("文件下载成功: {} ({} bytes)", filePath, bytes.length);
            return bytes;
        } catch (SdkException e) {
            log.error("文件下载失败: {}", filePath, e);
            throw new RuntimeException("文件下载失败: " + filePath, e);
        }
    }

    /**
     * 删除文件
     *
     * @param filePath 文件存储路径（完整 S3 对象键）
     * @return 是否删除成功
     */
    public boolean deleteFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            log.warn("文件路径为空，跳过删除");
            return false;
        }
        
        log.info("开始删除文件: {}", filePath);
        
        try {
            // filePath 已经是完整的 S3 对象键
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(filePath)
                    .build());
            log.info("文件删除成功: {}", filePath);
            return true;
        } catch (Exception e) {
            log.error("文件删除失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 检查文件是否存在
     *
     * @param filePath 文件存储路径
     * @return 是否存在
     */
    public boolean fileExists(String filePath) {
        try {
            String objectKey = basePath + filePath;
            // v2 无 doesObjectExist：headObject 成功即存在，NoSuchKey 即不存在
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("检查文件存在失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 生成文件存储路径
     */
    private String generateFilePath(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("%s/%s/", prefix, datePath);
    }

    /**
     * 生成唯一文件名
     */
    private String generateFileName(String originalFilename) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitizeFilename(originalFilename);
        return uuid + "_" + safeName;
    }

    /**
     * 清理文件名中的特殊字符
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unknown";
        }
        return filename.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }
}
