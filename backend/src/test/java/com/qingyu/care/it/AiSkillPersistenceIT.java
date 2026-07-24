package com.qingyu.care.it;

import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 AiSkill.toolIds（@ElementCollection）在真实 Postgres 容器持久化+回读正确。
 *
 * @author ruan
 */
class AiSkillPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private AiSkillRepository skillRepository;
    @Autowired
    private AiToolRepository toolRepository;

    @Test
    void skillToolIdsRoundTrip() {
        AiTool t = toolRepository.saveAndFlush(AiTool.builder()
                .name("query_order").description("查订单")
                .urlTemplate("https://api.example.com/o/{id}").httpMethod("GET")
                .timeoutMs(5000).maxResponseChars(4000).enabled(true).build());

        AiSkill saved = skillRepository.saveAndFlush(AiSkill.builder()
                .name("退货").content("处理退货...").toolIds(Set.of(t.getId()))
                .enabled(true).build());

        AiSkill reloaded = skillRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getToolIds()).containsExactly(t.getId());
        assertThat(reloaded.getContent()).isEqualTo("处理退货...");
    }

    @Test
    void findByEnabledTrueReturnsEnabledSkill() {
        skillRepository.saveAndFlush(AiSkill.builder()
                .name("礼貌").content("始终礼貌").toolIds(Set.of()).enabled(true).build());
        assertThat(skillRepository.findByEnabledTrue())
                .anyMatch(s -> "始终礼貌".equals(s.getContent()));
    }
}
