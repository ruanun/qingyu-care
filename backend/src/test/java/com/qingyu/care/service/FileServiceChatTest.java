package com.qingyu.care.service;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceChatTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private FileStorageService fileStorageService;
    @Mock
    private S3Client s3Client;
    @InjectMocks
    private FileService fileService;

    @Test
    void 上传聊天附件返回的是可直接喂给openStream的对象键() {
        FileInfo info = new FileInfo();
        info.setUrl("knowledge/chat/2026/08/05/ab12_截图.png");
        when(fileStorageService.of(any(byte[].class))
                .setPath(any())
                .setSaveFilename(any())
                .setContentType(any())
                .upload()).thenReturn(info);

        String key = fileService.uploadChatBytes(new byte[]{1, 2, 3}, "截图.png", "image/png");

        // 与 downloadFile 的口径一致：存的是 FileInfo.getUrl()，不是自己拼的 path+filename
        assertThat(key).isEqualTo("knowledge/chat/2026/08/05/ab12_截图.png");
    }
}
