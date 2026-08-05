package com.qingyu.care.controller.open;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.open.OpenChatRequest;
import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ChatStreamSseMapper;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.media.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenChatController#toInternal 附件透传单测（I-2 终审发现：删掉 {@code .attachmentIds(...)}
 * 那一行，整条链仍编译运行、200 返回，只是 Widget（开放 API 唯一渠道）用户带的附件 id
 * 全部丢失——是这条特性对开放 API 用户的死开关）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class OpenChatControllerTest {

    @Mock ChatService chatService;
    @Mock ApiUsageService apiUsageService;
    @Mock ChatFeedbackService chatFeedbackService;
    @Mock ChatStreamSseMapper sseMapper;
    @Mock ChatAttachmentService chatAttachmentService;
    @Mock FileService fileService;
    @InjectMocks OpenChatController controller;

    @Test
    void completions透传attachmentIds到内部ChatRequest() {
        OpenChatRequest req = new OpenChatRequest();
        req.setMessage("这是截图");
        req.setAttachmentIds(List.of(5L, 6L));
        when(chatService.chat(any())).thenReturn(ChatResponse.builder().sessionId("s-1").build());

        controller.completions(req);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertThat(captor.getValue().getAttachmentIds()).containsExactly(5L, 6L);
    }

    @Test
    void completions没带附件时内部ChatRequest的attachmentIds为空() {
        OpenChatRequest req = new OpenChatRequest();
        req.setMessage("你好");
        when(chatService.chat(any())).thenReturn(ChatResponse.builder().sessionId("s-2").build());

        controller.completions(req);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertThat(captor.getValue().getAttachmentIds()).isNull();
    }
}
