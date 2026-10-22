package com.qingyu.care.controller.open;

import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.service.ChatStreamSseMapper;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 守「框架层的客户端错误真的被分派到 {@link OpenApiExceptionHandler#handleMalformedRequest}」这一段。
 *
 * <p>{@link OpenApiExceptionHandlerTest} 直接调用处理方法，证明不了分派：把某个异常类从
 * {@code @ExceptionHandler} 列表里删掉，那边照样绿，而线上已经退回 500。这里走 Spring 真实的
 * 异常解析链（standalone MockMvc，免 Docker），每个异常是由真实请求触发的，不是手工 new 出来的。
 *
 * <p>鉴权拦截器没挂——这些请求本就在进入控制器方法之前失败，与鉴权无关。
 */
@ExtendWith(MockitoExtension.class)
class OpenApiMalformedRequestDispatchTest {

    @Mock private ChatService chatService;
    @Mock private ApiUsageService apiUsageService;
    @Mock private ChatFeedbackService chatFeedbackService;
    @Mock private ChatStreamSseMapper sseMapper;
    @Mock private ChatAttachmentService chatAttachmentService;
    @Mock private FileService fileService;
    @InjectMocks private OpenChatController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OpenApiExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void 非法JSON请求体_400() throws Exception {
        mvc.perform(post("/open/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("请求体格式错误")));
        verifyNoInteractions(chatService);
    }

    @Test
    void 上传端点收到非multipart请求_400() throws Exception {
        mvc.perform(post("/open/v1/chat/files").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("multipart/form-data")));
    }

    @Test
    void multipart缺文件_400并点名file() throws Exception {
        mvc.perform(multipart("/open/v1/chat/files").param("sessionId", "s1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("缺少必需参数: file")));
        verifyNoInteractions(chatAttachmentService);
    }

    @Test
    void 缺query参数_400并点名() throws Exception {
        mvc.perform(get("/open/v1/chat/files/1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("缺少必需参数: sessionId")));
    }

    @Test
    void 路径参数类型不符_400() throws Exception {
        mvc.perform(get("/open/v1/chat/files/abc").param("sessionId", "s1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("参数类型错误: id")));
    }
}
