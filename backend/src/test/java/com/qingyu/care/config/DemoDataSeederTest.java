package com.qingyu.care.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示会话的时间上限：会话不可能发生在未来。
 *
 * <p>播种时刻是任意的，而演示会话的营业时段是随机 9~20 点——上午播种时当天那条会落到未来
 * （实测 15:50 播种，仪表盘显示「今天 18:29」）。不 clamp 的话这条问题只在「上午播种」时才显现，
 * 下午播种的人永远看不到，所以钉成单测。
 *
 * @author ruan
 */
class DemoDataSeederTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 15, 50);

    @Test
    void 过去的时间原样保留() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 22, 10, 1);
        assertThat(DemoDataSeeder.clampToPast(start, NOW)).isEqualTo(start);
    }

    @Test
    void 未来时间被收到现在之前() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 22, 18, 29);
        assertThat(DemoDataSeeder.clampToPast(start, NOW)).isEqualTo(NOW.minusMinutes(5));
    }

    @Test
    void 恰好等于上限时不动_超过一分钟才收() {
        assertThat(DemoDataSeeder.clampToPast(NOW.minusMinutes(5), NOW)).isEqualTo(NOW.minusMinutes(5));
        assertThat(DemoDataSeeder.clampToPast(NOW.minusMinutes(4), NOW)).isEqualTo(NOW.minusMinutes(5));
    }

    @Test
    void 上限留出余量_给同一条会话里往后排的消息() {
        // 会话时间被收到 now-5min 后，该会话的第一条消息还会在它之后几分钟，
        // 余量太小会让消息也落到未来
        LocalDateTime clamped = DemoDataSeeder.clampToPast(NOW.plusHours(3), NOW);
        assertThat(clamped.plusMinutes(4)).isBefore(NOW);
    }
}
