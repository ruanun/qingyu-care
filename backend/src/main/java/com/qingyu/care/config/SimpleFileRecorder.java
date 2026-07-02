package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.core.upload.FilePartInfo;
import org.springframework.stereotype.Component;

/**
 * 文件记录器实现
 * x-file-storage 需要此接口来记录文件信息
 * 在简单场景下可以不持久化文件记录，直接返回即可
 */
@Slf4j
@Component
public class SimpleFileRecorder implements FileRecorder {

    @Override
    public boolean save(FileInfo fileInfo) {
        log.debug("保存文件记录: {}", fileInfo.getUrl());
        // 简单实现：不持久化文件记录到数据库
        // 文件信息已在 Document 实体中保存
        return true;
    }

    @Override
    public FileInfo getByUrl(String url) {
        log.debug("获取文件记录: {}", url);
        // 简单实现：返回 null，不从数据库获取
        // 因为我们的文件信息保存在 Document 实体中
        return null;
    }

    @Override
    public boolean delete(String url) {
        log.debug("删除文件记录: {}", url);
        // 简单实现：直接返回成功
        return true;
    }

    @Override
    public void update(FileInfo fileInfo) {
        log.debug("更新文件记录: {}", fileInfo.getUrl());
        // 简单实现：不做任何操作
    }

    @Override
    public void saveFilePart(FilePartInfo filePartInfo) {
        log.debug("保存文件分片记录: {}", filePartInfo.getUploadId());
        // 简单实现：不做任何操作
    }

    @Override
    public void deleteFilePartByUploadId(String uploadId) {
        log.debug("删除文件分片记录: {}", uploadId);
        // 简单实现：不做任何操作
    }
}
