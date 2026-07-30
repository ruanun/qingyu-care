package com.qingyu.care.service.realtime;

/** 实时订阅键构造（单一来源，防各处手拼漂移）。 */
public final class RealtimeKeys {

    private RealtimeKeys() {}

    /** 用户端每会话一条 inbox 流。 */
    public static String session(String sessionId) {
        return "session:" + sessionId;
    }

    /** 坐席端每人一条流（含租户，隔离 + 便于按租户枚举在线坐席）。 */
    public static String agent(Long tenantId, Long agentUserId) {
        return "agent:" + tenantId + ":" + agentUserId;
    }

    /** 某租户下所有坐席键前缀（枚举在线坐席 / 广播用）。 */
    public static String agentPrefix(Long tenantId) {
        return "agent:" + tenantId + ":";
    }

    /**
     * 某租户的坐席广播键：所有坐席订阅流额外 merge 订阅此键，扇出事件（新待接/presence 等）
     * 一次 publish 即可跨实例送达该租户全部在线坐席，无需枚举本地在线坐席逐个 publish。
     */
    public static String agentBroadcast(Long tenantId) {
        return "agent-bcast:" + tenantId;
    }
}
