package com.qingyu.care.support;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 测试用「慢速滴灌」HTTP 服务端：回 200 + chunked 之后每 {@code intervalMs} 吐 1 字节，永不结束。
 *
 * <p>只要间隔小于客户端的 socket 空闲超时，单段超时就一个都不会触发——用来证明出站调用有
 * <b>墙钟总时长</b>上限（2026-09-30 安全审查 H3）。监听在环回地址，调用方需打开对应的私网开关或直接用工厂。
 *
 * @author ruan
 */
public class DripHttpServer implements AutoCloseable {

    private final ServerSocket server;

    public DripHttpServer(String contentType, long intervalMs) throws Exception {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    skipRequestHeaders(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    while (true) {
                        out.write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        Thread.sleep(intervalMs);
                    }
                } catch (Exception ignored) {
                    // 客户端断开后写失败，进入下一轮 accept
                }
            }
        }, "drip-server");
        t.setDaemon(true);
        t.start();
    }

    private static void skipRequestHeaders(InputStream in) throws Exception {
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) return;
            matched = (b == end[matched]) ? matched + 1 : (b == end[0] ? 1 : 0);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 用 localhost 主机名：出站工厂按主机名钉 DNS。 */
    public String url(String path) {
        return "http://localhost:" + port() + path;
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
