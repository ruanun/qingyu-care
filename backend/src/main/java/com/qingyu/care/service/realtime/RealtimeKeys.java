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
}
