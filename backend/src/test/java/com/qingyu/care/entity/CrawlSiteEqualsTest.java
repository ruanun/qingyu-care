package com.qingyu.care.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@link CrawlSite} 的判等语义：必须按 {@code id} 判等。
 *
 * <p>不显式声明 {@code @EqualsAndHashCode} 就会直接继承 {@link BaseTenantEntity} 上
 * {@code @Data} 生成的版本——那个版本只比较 {@code tenantId}/{@code appId}，于是两个
 * 内容完全不同、但都还没落租户上下文（两者皆为 null）的站点实例会被判定为"相等"，
 * 放进 {@code Set}/{@code Map} 去重时会静默合并。{@code ChatAttachment} 踩过同一个坑。
 *
 * @author ruan
 */
class CrawlSiteEqualsTest {

    @Test
    @DisplayName("租户上下文均为 null 时，不同 id 的站点不相等")
    void distinct_ids_are_not_equal_even_without_tenant_context() {
        CrawlSite a = CrawlSite.builder().id(1L).entryUrl("https://a.com/help/").build();
        CrawlSite b = CrawlSite.builder().id(2L).entryUrl("https://b.com/docs/").build();

        assertThat(a.getTenantId()).isNull();
        assertThat(b.getTenantId()).isNull();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("id 相同即相等，其余字段不参与判等")
    void same_id_is_equal_regardless_of_other_fields() {
        CrawlSite a = CrawlSite.builder().id(7L).entryUrl("https://a.com/help/").maxPages(50).build();
        CrawlSite b = CrawlSite.builder().id(7L).entryUrl("https://b.com/docs/").maxPages(200).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
