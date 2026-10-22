package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 机制级验证：检索失败事务隔离所依赖的 Spring/PG 不变式。
 *
 * <p>用 SELECT 1/0（PG division_by_zero，SQLSTATE 22012）确定性地触发一次「会毒化事务」的 SQL 失败，
 * 镜像 ChatServiceImpl.searchKnowledge 中关键词/向量检索 SQL 失败的效果（且吞异常做 fail-soft）：
 * <ul>
 *   <li>正例：失败 SQL 跑在 REQUIRES_NEW 只读新事务里 → 只毒化该独立连接，外层事务连接干净、后续查询正常；</li>
 *   <li>反例：失败 SQL 跑在外层同一事务里（吞异常也救不回）→ 外层连接被毒化，后续查询报
 *       「current transaction is aborted」——坐实「隔离是必需的」。</li>
 * </ul>
 *
 * <p>不经 chat() 全链路：关键词 SQL 用 plainto_tsquery（任意输入不报错）、真实 SQL 失败只源于
 * schema 级（content_tsv 缺失，共享容器里恒存在）无法自然复现，且 chat() 端到端受 LLM 依赖阻塞
 * （沿用既定 LLM-IT 跳过取舍）。故以本不变式验证替代，chat() 完整链路 fail-soft 待真机 re-smoke 复验。
 *
 * @author ruan
 */
@Transactional
class RetrievalTxIsolationIT extends AbstractPostgresIT {

    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 独立只读新事务里的检索SQL失败_不毒化外层事务() {
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inner.setReadOnly(true);
        inner.executeWithoutResult(status -> {
            try {
                jdbcTemplate.queryForObject("SELECT 1/0", Integer.class);
            } catch (Exception e) {
                // fail-soft：镜像 searchKnowledge 顶层 try/catch 吞异常返回空
            }
        });

        // 外层（类级 @Transactional）连接未被毒化，仍可正常查询
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void 反例_同事务里检索SQL失败_毒化外层_证明隔离是必需的() {
        try {
            jdbcTemplate.queryForObject("SELECT 1/0", Integer.class);
        } catch (Exception e) {
            // 即便吞掉异常，外层事务连接已在 PG 端被置为 aborted
        }

        assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("current transaction is aborted");
    }
}
