package com.qingyu.care.it;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** @Modifying 累加缺事务会抛 TransactionRequiredException 被吞、计量静默丢失。 */
class ApiUsageMeteringIT extends AbstractPostgresIT {

    private static final Long TENANT = 90001L;
    private static final Long APP = 90002L;

    @Autowired
    private ApiUsageService apiUsageService;
    @Autowired
    private SysApiUsageRepository repository;

    @AfterEach
    void cleanup() {
        repository.findAll().stream()
                .filter(u -> APP.equals(u.getAppId()))
                .forEach(repository::delete);
    }

    @Test
    void record成功落库并累加() {
        apiUsageService.record(TENANT, APP, 1, 5);
        apiUsageService.record(TENANT, APP, 1, 7);

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<SysApiUsage> rows = repository.findAll().stream()
                    .filter(u -> APP.equals(u.getAppId())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getCallCount()).isEqualTo(2);
            assertThat(rows.get(0).getMessageCount()).isEqualTo(2);
            assertThat(rows.get(0).getTokenCount()).isEqualTo(12);
        });
    }
}
