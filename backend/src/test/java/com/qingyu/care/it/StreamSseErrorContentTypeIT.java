package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MM-9 真机冒烟发现（Important）：SSE 端点上抛出的异常，若客户端按标准 SSE 惯例声明
 * {@code Accept: text/event-stream}，会在 {@code OpenApiExceptionHandler}/
 * {@code GlobalExceptionHandler} 写响应体那一步撞上 {@code HttpMediaTypeNotAcceptableException}
 * （POJO 只有 JSON 转换器能处理，与 Accept 头不匹配）——开放 API 这条还会连锁触发 Tomcat 向
 * {@code /error} 的内部转发（该路径不在 Sa-Token 登录豁免名单里）→ {@code NotLoginException}
 * →同样的内容协商失败→一路兜到 Tomcat 原生错误页（HTML + 完整堆栈 + 一个跟真正原因无关的
 * 401）；管理端这条没有那层连锁，但结果同样糟糕：空 body + 500，客户端连错误原因都看不到。
 *
 * <p>⚠️ 本类必须用真实内嵌 Servlet 容器（{@code RANDOM_PORT}）+ 真实 HTTP 客户端复现——
 * MockMvc（{@code MOCK} web environment）复现不出这条坑：MockMvc 的模拟分发不会真的触发
 * {@code writeWithMessageConverters} 对 Accept 头的强内容协商失败路径，同一份请求体在 MockMvc
 * 下永远拿到正确的 404+JSON，掩盖了真实 Servlet 容器下的行为。这是本次诊断过程本身踩出来的
 * 教训，为后来人少走弯路而记录。
 *
 * @author ruan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamSseErrorContentTypeIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean
    private ModelRouterService modelRouterService;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();

    private record Actor(Application app, String token) {}

    private ChatSession seedSession(String sessionId, Application app) {
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        return sessionRepository.saveAndFlush(session);
    }

    private Actor seedActor(String suffix) throws Exception {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("SSE错误IT租户-" + suffix).code("SSEERR_" + suffix)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        Application app = new Application();
        app.setName("SSE错误IT应用-" + suffix);
        app.setTenant(tenant);
        app.setAppKey("sseerr_" + suffix);
        app.setAppSecret("sec_" + suffix);
        app.setEnabled(true);
        app = applicationRepository.save(app);
        String username = "sseerr_user_" + suffix;
        userRepository.save(User.builder()
                .username(username).password(BCrypt.hashpw("pw123456"))
                .nickname("SSE错误IT用户").role(UserRole.TENANT_MEMBER).tenant(tenant).enabled(true).build());
        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"pw123456\"}"))
                .build();
        HttpResponse<String> loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        JsonNode node = om.readTree(loginResp.body());
        String token = node.path("data").path("token").asString();
        return new Actor(app, token);
    }

    /** 断言响应是干净的 JSON 错误体：不含堆栈/异常类名/包名，不是 Tomcat 默认错误页。 */
    private static void assertCleanJsonError(HttpResponse<String> resp, int expectedStatus) {
        assertThat(resp.statusCode()).isEqualTo(expectedStatus);
        assertThat(resp.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        String body = resp.body();
        assertThat(body).doesNotContain("<!doctype html", "<html", "Exception", "com.qingyu.care", "\tat ");
    }

    @Test
    void 开放API流式端点_带不存在附件id_返回干净的404JSON_即便客户端声明AcceptEventStream() throws Exception {
        Application app = seedApp("sseerro" + System.nanoTime(), "http://sseerro.example.com");
        String sessionId = "sseerro-sess-" + System.nanoTime();
        seedSession(sessionId, app);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/open/v1/chat/stream"))
                .header("X-App-Id", app.getAppKey())
                .header("X-Api-Key", app.getAppSecret())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")   // 真实 SSE 客户端的标准声明
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sessionId\":\"" + sessionId + "\",\"message\":\"x\",\"attachmentIds\":[999999]}"))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertCleanJsonError(resp, 404);
        assertThat(resp.body()).contains("\"code\":404").contains("附件不存在");
    }

    @Test
    void 管理端流式端点_带不存在附件id_返回干净的404JSON_即便客户端声明AcceptEventStream() throws Exception {
        Actor actor = seedActor("sseerra" + System.nanoTime());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/chat/stream"))
                .header("satoken", actor.token())
                .header("X-App-Id", String.valueOf(actor.app().getId()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"x\",\"attachmentIds\":[999999]}"))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertCleanJsonError(resp, 404);
        assertThat(resp.body()).contains("\"code\":404").contains("附件不存在");
    }
}
