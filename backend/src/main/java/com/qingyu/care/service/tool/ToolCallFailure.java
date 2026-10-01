package com.qingyu.care.service.tool;

/**
 * 工具调用失败，且<b>消息可以原样交给模型</b>：只由本包在确知消息不含 URL / 凭据时抛出
 * （守卫拒绝只含主机名、参数缺失只含参数名、上游状态码与正文片段、超时）。
 *
 * <p>为什么要单独一个类型（2026-09-30 安全审查）：失败消息会进模型上下文、可被访客诱导复述。
 * 此前按「{@code IllegalArgumentException} / {@code IllegalStateException} 就安全」判断，可这两个是 JDK 与各个库
 * 都会抛的通用类型——{@code URI.create(url)} 抛的 IAE 消息里就带完整 URL，而 URL 的查询串里可能直接写着 key。
 * 「是不是安全」应该由抛出方声明，而不是由异常的通用类型推断。
 *
 * @author ruan
 */
public class ToolCallFailure extends RuntimeException {

    public ToolCallFailure(String message) {
        super(message);
    }

    public ToolCallFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
