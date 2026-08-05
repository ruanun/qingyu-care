package com.qingyu.care.service;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

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

    @Test
    void 上传聊天附件时原始文件名必须先经过清洗才交给存储服务() {
        FileInfo info = new FileInfo();
        info.setUrl("knowledge/chat/2026/08/05/ab12_evil.png");
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        when(fileStorageService.of(any(byte[].class))
                .setPath(pathCaptor.capture())
                .setSaveFilename(filenameCaptor.capture())
                .setContentType(any())
                .upload()).thenReturn(info);

        // 路径穿越型文件名：绝不能被原样传给 setSaveFilename，必须先经 generateFileName -> sanitizeFilename 清洗
        fileService.uploadChatBytes(new byte[]{1, 2, 3}, "../../evil.png", "image/png");

        // sanitizeFilename 的清洗契约是"剔除路径分隔符"（. 本身是允许字符，用于保留扩展名），
        // 没了 / 和 \，残留的 ".." 只是字面点号，不再具备目录穿越能力
        assertThat(filenameCaptor.getValue())
                .as("传给存储服务的文件名不能含路径分隔符")
                .doesNotContain("/", "\\");
        assertThat(pathCaptor.getValue())
                .as("聊天附件必须落在 chat/ 前缀下，不能被越权改写")
                .startsWith("chat/");
    }

    @Test
    void 流式读取时装配的bucket与key与传入storageKey完全一致_不带任何额外前缀() {
        ReflectionTestUtils.setField(fileService, "bucketName", "qingyu-care");
        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        when(s3Client.getObject(requestCaptor.capture())).thenReturn(null);

        fileService.openStream("knowledge/chat/2026/08/05/ab12_截图.png");

        GetObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("qingyu-care");
        // key 必须是传入的 storageKey 本身：openStream 收到的 storageKey 已经是完整对象键，
        // 不能像 fileExists 那样再叠加 basePath 之类的前缀
        assertThat(request.key()).isEqualTo("knowledge/chat/2026/08/05/ab12_截图.png");
    }
}
