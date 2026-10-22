package com.qingyu.care.it;

import com.qingyu.care.dto.AgentSessionDTO;
import com.qingyu.care.repository.AgentSessionDao;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「我的会话 + 未读数」那条原生 SQL 的口径与租户隔离。
 *
 * <p>⚠️ 原生 SQL 绕开 Hibernate {@code @Filter}，租户隔离必须靠 SQL 里显式的 tenant_id 条件，
 * 而那正是本仓库反复出事的地方，故必须有一条真库用例守着。
 *
 * @author ruan
 */
class AgentSessionUnreadIT extends AbstractPostgresIT {

    @Autowired AgentSessionDao dao;
    @Autowired JdbcTemplate jdbc;

    private static final Long TENANT_A = 1001L;
    private static final Long TENANT_B = 1002L;
    private static final Long APP_A = 2001L;
    private static final Long AGENT = 3001L;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM message_read_receipt");
        jdbc.update("DELETE FROM chat_message WHERE session_id IN "
                + "(SELECT id FROM chat_session WHERE tenant_id IN (?, ?))", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM chat_session WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
    }

    /** 播种一个已接管会话，返回它的主键。 */
    private Long seedSession(Long tenantId, Long appId, Long agentId, String sessionId) {
        jdbc.update("INSERT INTO chat_session (session_id, status, agent_id, tenant_id, app_id, "
                + "created_at, updated_at) VALUES (?, 'AGENT_PROCESSING', ?, ?, ?, ?, ?)",
                sessionId, agentId, tenantId, appId, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE session_id = ?", Long.class, sessionId);
    }

    /** 播种一条消息，返回它的主键。 */
    private Long seedMessage(Long sessionPk, String role, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO chat_message (session_id, role, content, created_at) "
                + "VALUES (?, ?, '内容', ?)", sessionPk, role, createdAt);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM chat_message WHERE session_id = ?", Long.class, sessionPk);
    }

    private void seedReceipt(Long sessionPk, Long upTo) {
        jdbc.update("INSERT INTO message_read_receipt "
                + "(session_id, reader_type, reader_id, up_to_message_id, read_at) "
                + "VALUES (?, 'AGENT', ?, ?, ?)", sessionPk, AGENT, upTo, LocalDateTime.now());
    }

    @Test
    @DisplayName("未读数只数访客消息、且只数水位线之后的")
    void 未读数口径正确() {
        Long sid = seedSession(TENANT_A, APP_A, AGENT, "s-1");
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        seedMessage(sid, "USER", base);                       // 水位线之前
        Long second = seedMessage(sid, "AGENT", base.plusMinutes(1));
        seedMessage(sid, "USER", base.plusMinutes(2));        // 未读 1
        seedMessage(sid, "AGENT", base.plusMinutes(3));       // 坐席自己发的，不算未读
        seedMessage(sid, "USER", base.plusMinutes(4));        // 未读 2
        seedReceipt(sid, second);

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).unreadCount())
                .as("只数水位线之后的 USER 消息，AGENT 消息不算")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("从无回执时未读数等于全部访客消息数")
    void 无回执时未读数为全部访客消息() {
        Long sid = seedSession(TENANT_A, APP_A, AGENT, "s-2");
        LocalDateTime base = LocalDateTime.now().minusMinutes(5);
        seedMessage(sid, "USER", base);
        seedMessage(sid, "USER", base.plusMinutes(1));
        seedMessage(sid, "AGENT", base.plusMinutes(2));
        // 不播种回执，走 COALESCE(up_to_message_id, 0) 兜底

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result.get(0).unreadCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("跨租户查不到别人的会话")
    void 租户隔离() {
        seedSession(TENANT_A, APP_A, AGENT, "s-mine");
        seedSession(TENANT_B, APP_A, AGENT, "s-other");   // 同一个 agentId，但属于别的租户

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result).extracting(AgentSessionDTO::sessionId)
                .as("原生 SQL 绕开 @Filter，租户隔离只能靠 SQL 里显式的 tenant_id 条件")
                .containsExactly("s-mine");
    }

    @Test
    @DisplayName("按最后一条消息时间倒序，不受 updated_at 影响")
    void 排序用最后消息时间() {
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        Long older = seedSession(TENANT_A, APP_A, AGENT, "s-old");
        Long newer = seedSession(TENANT_A, APP_A, AGENT, "s-new");
        seedMessage(older, "USER", base);                    // 最后消息更早
        seedMessage(newer, "USER", base.plusMinutes(30));    // 最后消息更晚
        // 把「最后消息更早」那条的 updated_at 改成最新——若按 updated_at 排序，它会错误地排前面
        jdbc.update("UPDATE chat_session SET updated_at = ? WHERE id = ?", LocalDateTime.now(), older);

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result).extracting(AgentSessionDTO::sessionId)
                .as("排序键必须是 MAX(m.created_at)；用 updated_at 会被打标签/改摘要/转接带动")
                .containsExactly("s-new", "s-old");
    }
}
