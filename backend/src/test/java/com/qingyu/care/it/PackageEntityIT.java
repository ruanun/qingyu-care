package com.qingyu.care.it;

import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tool_package.manifest} 必须是 {@code text}，不能设列宽。
 *
 * <p>长度由包里有多少工具、skill 的 markdown 有多长决定，没有可预估的上限。
 * {@code McpTool.description} 就是设成 {@code varchar(1000)} 之后在真机接入 context7 时
 * 直接抛 {@code value too long for type character varying(1000)}，整个 server 接不进来
 * （docs/dev/子系统要点.md「MCP 接入」第 10 条）。本 IT 与 {@code McpToolLongDescriptionIT} 同源，
 * 同一个坑不换位置再踩一次。
 *
 * <p>⚠️ 本 IT 只守新建库。存量库另需迁移 SQL——{@code ddl-auto: update} 只加列、
 * 不改已有列的类型，改了注解也不会动线上那一列，症状与改之前一模一样。
 *
 * @author ruan
 */
class PackageEntityIT extends AbstractPostgresIT {

    @Autowired
    private ToolPackageRepository packageRepository;

    @Test
    void 超长manifest能落库() {
        // 2000 字符，稳超任何 varchar(1000)/varchar(255)；真实包的 manifest 轻易过万
        String longManifest = "{\"manifestVersion\":1,\"note\":\"" + "x".repeat(2000) + "\"}";

        ToolPackage saved = packageRepository.saveAndFlush(ToolPackage.builder()
                .code("long_manifest_pkg")
                .name("超长 manifest 包")
                .status(PackageStatus.DRAFT)
                .manifest(longManifest)
                .build());

        ToolPackage reloaded = packageRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getManifest()).isEqualTo(longManifest);
    }

    @Test
    void 按code查唯一且能按状态过滤() {
        packageRepository.saveAndFlush(ToolPackage.builder()
                .code("published_pkg").name("已发布包")
                .status(PackageStatus.PUBLISHED).manifest("{}").build());
        packageRepository.saveAndFlush(ToolPackage.builder()
                .code("draft_pkg").name("草稿包")
                .status(PackageStatus.DRAFT).manifest("{}").build());

        assertThat(packageRepository.findByCode("published_pkg")).isPresent();
        assertThat(packageRepository.existsByCode("draft_pkg")).isTrue();
        assertThat(packageRepository.findByStatusOrderByNameAsc(PackageStatus.PUBLISHED))
                .extracting(ToolPackage::getCode)
                .contains("published_pkg")
                .doesNotContain("draft_pkg");
    }
}
