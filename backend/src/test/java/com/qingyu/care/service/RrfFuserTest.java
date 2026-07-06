package com.qingyu.care.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RRF 融合纯逻辑：多列表按 1/(k+rank) 求和排序、按 id 去重、截断 topK。 */
class RrfFuserTest {

    private RrfFuser.Item item(String id) {
        return new RrfFuser.Item(id, "内容" + id);
    }

    @Test
    void 两列表交叠项得分更高_排前() {
        List<RrfFuser.Item> a = List.of(item("a"), item("b"), item("c"));
        List<RrfFuser.Item> b = List.of(item("b"), item("d"), item("a"));

        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a, b), 60, 10);

        // b/a 两列表交叠得分最高，排前两位；c/d 各自只出现一次，
        // d 在其列表中排名(第2)早于 c 在其列表中排名(第3)，故 RRF 分数 d > c，d 排在 c 之前。
        assertThat(fused).extracting(RrfFuser.Item::id).containsExactly("b", "a", "d", "c");
    }

    @Test
    void 按id去重_同一项只出现一次() {
        List<RrfFuser.Item> a = List.of(item("x"), item("y"));
        List<RrfFuser.Item> b = List.of(item("x"));
        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a, b), 60, 10);
        assertThat(fused).extracting(RrfFuser.Item::id).containsExactly("x", "y");
    }

    @Test
    void 截断到topK() {
        List<RrfFuser.Item> a = List.of(item("a"), item("b"), item("c"));
        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a), 60, 2);
        assertThat(fused).hasSize(2);
    }

    @Test
    void 单列表为空不报错() {
        List<RrfFuser.Item> a = List.of(item("a"));
        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a, List.of()), 60, 10);
        assertThat(fused).extracting(RrfFuser.Item::id).containsExactly("a");
    }
}
