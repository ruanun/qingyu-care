package com.qingyu.care.it;

import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 上下文能对着真实 PG(容器) 启动，且默认管理员已建。 */
class SmokeIT extends AbstractPostgresIT {

    @Autowired
    private UserRepository userRepository;

    @Test
    void 上下文加载且默认管理员存在() {
        assertThat(userRepository.findByUsername("admin")).isPresent();
    }
}
