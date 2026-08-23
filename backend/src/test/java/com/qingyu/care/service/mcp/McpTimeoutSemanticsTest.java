package com.qingyu.care.service.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住 {@code McpServer.timeoutMs} 那一个值实际控制的**两层不同语义**的超时。
 *
 * <p>{@link McpConnectionFactory} 把同一个 {@code timeoutMs} 同时喂给了：
 * <ul>
 *   <li>Reactor Netty 的 {@code responseTimeout} —— javadoc 明写是
 *       「相邻两次网络读之间的最大间隔」（底层 {@code ReadTimeoutHandler}，请求发出后装上、
 *       响应完全收完才卸掉），**不是**响应总时长；
 *   <li>MCP SDK 的 {@code requestTimeout} —— {@code McpClientSession} 里是 Reactor 的
 *       {@code .timeout(...)} 操作符，**是**单个 JSON-RPC 请求的响应总时长上限。
 * </ul>
 *
 * <p>两者混淆会得出完全相反的运维结论，故用真实 TCP 时序把它们分别测出来，而不是读注释推断：
 * 一个持续吐进度、总耗时 20 秒的慢工具，在「读间隔」语义下正常返回，在「总时长」语义下必失败。
 * 实测结论是**后者说了算**——{@code timeoutMs} 是单次 MCP 请求的总时长硬上限，远端吐再多
 * 进度也不豁免，故慢工具只能靠调大该值救，不能指望远端心跳。
 *
 * <p>⚠️ 本测试起真实 {@link HttpServer} 并依赖 sleep 的相对时序，阈值间留了 3 倍以上余量
 * （读间隔 {@value #READ_IDLE_SHORT_MS}ms vs 心跳 {@value #HEARTBEAT_INTERVAL_MS}ms，
 * 总耗时 {@value #SERVER_WORK_MS}ms）。收窄余量会让它在负载高的机器上变 flaky。
 *
 * @author ruan
 */
class McpTimeoutSemanticsTest {

    /** 短读空闲上限：小于服务器总耗时，故「静默到底」必然撞上、「持续心跳」必然撞不上。 */
    private static final int READ_IDLE_SHORT_MS = 800;
    /** 长上限：本测试里等同于「不设限」，用来把另一层单独隔离出来观察。 */
    private static final int LIMIT_LONG_MS = 30_000;
    /** 服务器处理一次 tools/call 的总耗时，显著大于短上限。 */
    private static final int SERVER_WORK_MS = 2_500;
    /** 心跳间隔，显著小于短读空闲上限。 */
    private static final int HEARTBEAT_INTERVAL_MS = 200;

    /** 服务器静默到底不吐任何字节的工具。 */
    private static final String TOOL_SILENT = "silent";
    /** 服务器持续吐 SSE 心跳、最后才给结果的工具（真实慢工具的形态）。 */
    private static final String TOOL_HEARTBEAT = "heartbeat";

    private static final Pattern REQUEST_ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]*)\"");

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void netty_read_timeout_kills_a_silent_response() throws Exception {
        // 读空闲 800ms，服务器静默 2500ms 后才吐第一个字节 —— 读超时必然先触发。
        // 这条是下面那条的对照组：证明 responseTimeout 确实在工具调用路径上生效，
        // 否则「心跳能救回来」可能只是因为这层压根没装上。
        try (McpSyncClient client = client(READ_IDLE_SHORT_MS, LIMIT_LONG_MS)) {
            client.initialize();

            assertThatThrownBy(() -> client.callTool(new McpSchema.CallToolRequest(TOOL_SILENT, Map.of())))
                    .as("静默超过读空闲上限必须被 Netty 掐断")
                    .hasRootCauseInstanceOf(ReadTimeoutException.class);
        }
    }

    @Test
    void netty_read_timeout_is_an_idle_gap_not_a_total_deadline() throws Exception {
        // 同样是 800ms 读空闲、2500ms 总耗时，唯一区别是服务器每 200ms 吐一次心跳。
        // 成功 => responseTimeout 是「读间隔」；失败 => 它是「总时长」。
        try (McpSyncClient client = client(READ_IDLE_SHORT_MS, LIMIT_LONG_MS)) {
            client.initialize();

            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest(TOOL_HEARTBEAT, Map.of()));

            assertThat(result).isNotNull();
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    void sdk_request_timeout_is_a_total_deadline_that_heartbeats_cannot_save() throws Exception {
        // 读空闲放到 30s（等于不设限），只把 SDK 的 requestTimeout 收到 800ms。
        // 心跳照发，但若 SDK 那层是总时长上限，它照样在 800ms 处掐断。
        try (McpSyncClient client = client(LIMIT_LONG_MS, READ_IDLE_SHORT_MS)) {
            client.initialize();

            assertThatThrownBy(() -> client.callTool(new McpSchema.CallToolRequest(TOOL_HEARTBEAT, Map.of())))
                    // 根因必须是 java.util.concurrent 的那个 TimeoutException（Reactor 的 .timeout 抛的），
                    // 而不是 Netty 的 ReadTimeoutException——两者都叫「超时」，但只有前者证明
                    // 掐断发生在 SDK 那层、心跳无法豁免。Netty 的 ReadTimeoutException 继承自
                    // io.netty.handler.timeout.TimeoutException，与此处断言的类型无继承关系。
                    .as("持续心跳仍被掐断，说明 SDK requestTimeout 是总时长上限")
                    .hasRootCauseInstanceOf(TimeoutException.class);
        }
    }

    /**
     * 复刻 {@link McpConnectionFactory#create} 的客户端构造，只把两层超时拆成独立参数。
     * 走的是同一个包级 {@code buildHttpClient}（含钉 IP 与禁重定向），只是绕开 SsrfGuard
     * ——它拒绝一切回环地址，而本测试的服务器必须在 127.0.0.1 上。
     */
    private McpSyncClient client(int readIdleMs, int requestTimeoutMs) throws IOException {
        HttpClient httpClient = McpConnectionFactory.buildHttpClient(
                "127.0.0.1", new InetAddress[] {InetAddress.getByName("127.0.0.1")}, readIdleMs);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        McpClientTransport transport = WebClientStreamableHttpTransport.builder(webClientBuilder)
                .endpoint(endpoint)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
    }

    // ---------------- 假 MCP server（streamable HTTP 的最小可用子集） ----------------

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = group(METHOD, body);
        String id = group(REQUEST_ID, body);

        if ("initialize".equals(method)) {
            json(exchange, """
                    {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18",\
                    "capabilities":{"tools":{"listChanged":false}},\
                    "serverInfo":{"name":"fake-mcp","version":"1.0.0"}}}""".formatted(id));
            return;
        }
        if (method != null && method.startsWith("notifications/")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if ("tools/call".equals(method)) {
            if (body.contains("\"" + TOOL_HEARTBEAT + "\"")) {
                heartbeatThenResult(exchange, id);
            } else {
                silentThenResult(exchange, id);
            }
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    /** 全程一个字节不吐，到点才把整个 JSON 响应发出去。 */
    private void silentThenResult(HttpExchange exchange, String id) throws IOException {
        sleep(SERVER_WORK_MS);
        json(exchange, callToolResult(id));
    }

    /**
     * 立刻起 SSE 流，每 {@value #HEARTBEAT_INTERVAL_MS}ms 吐一行注释保活，最后才发结果事件。
     *
     * <p>心跳刻意**不带空行结尾**：SSE 里事件块以空行分隔，不闭合就不会被解码器 emit 成事件，
     * 于是既不给 SDK 送任何半成品消息、字节又真的到达了 Netty（读超时看的就是字节到达）。
     */
    private void heartbeatThenResult(HttpExchange exchange, String id) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (int elapsed = 0; elapsed < SERVER_WORK_MS; elapsed += HEARTBEAT_INTERVAL_MS) {
                sleep(HEARTBEAT_INTERVAL_MS);
                os.write(": keepalive\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.write(("event: message\ndata: " + callToolResult(id) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static String callToolResult(String id) {
        return """
                {"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"ok"}],\
                "isError":false}}""".formatted(id);
    }

    private static void json(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String group(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
