# Phase 3 剩余管理能力 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 Phase 3 的四项独立管理能力：统一配置中心（平台/租户配置）、共享模型池、操作审计日志、系统健康概览。

**Architecture:** 沿用现有分层（entity→repository→service→controller→dto）与前端页面模式（page + api + 菜单）。新增两张系统表 `sys_config`、`sys_audit_log`，给 `ai_model_config` 加 `scope` 列；共享模型路由改为「租户自有 ∪ 平台共享」并用原生查询绕过租户过滤；审计用自定义注解 `@AuditLog` + AOP 切面异步落库。

**Tech Stack:** Spring Boot 3.4.1 / Spring Data JPA / Sa-Token / JUnit5 + Mockito；React 19 + Vite + TS + shadcn/ui + axios。

---

## 约定与前置（所有 Task 通用）

- **不执行 `git commit`**（项目 CLAUDE.md 禁止）。原模板的 "Commit" 步骤一律替换为 **"检查点"**：编译通过 + 相关单测通过。提交由用户手动完成。
- 后端根目录：`D:/Code/m/qingyu-care/backend`；前端根目录：`D:/Code/m/qingyu-care/frontend`。
- 运行单个后端测试：`cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=<测试类名> test`
- 编译后端：`cd /d/Code/m/qingyu-care/backend && mvn -q compile`
- 前端类型检查+构建：`cd /d/Code/m/qingyu-care/frontend && pnpm build`
- 新单测用 `@ExtendWith(MockitoExtension.class)`，**不加载 Spring 上下文、不连数据库**，可独立运行。
- 建表由 `ddl-auto: update` 自动完成，无需手写 DDL；新增列对旧行为 NULL，兼容性在相应 Task 说明。
- 控制器路径前缀 `/v1/...`（context-path `/api`）；前端 api baseURL 已是 `/api/v1`，故前端调用写相对路径如 `/platform/config`。

---

## 文件结构总览

**后端新增：**
- `enums/OwnerScope.java` — 平台/租户归属枚举（配置与模型共用）
- `entity/SysConfig.java`、`repository/SysConfigRepository.java`
- `entity/SysAuditLog.java`、`repository/SysAuditLogRepository.java`
- `service/ConfigService.java`、`service/AuditLogService.java`、`service/PlatformService.java`
- `annotation/AuditLog.java`、`config/AuditLogAspect.java`
- `util/SecurityUtil.java`
- `dto/PlatformOverviewDTO.java`
- `controller/PlatformConfigController.java`、`controller/TenantConfigController.java`、`controller/AuditLogController.java`、`controller/PlatformController.java`

**后端修改：**
- `entity/AiModelConfig.java`（加 `scope`）
- `repository/AiModelConfigRepository.java`（加原生共享查询）
- `service/ModelRouterService.java`（合并路由 + init 改造 + 共享 CRUD）
- `service/AuthService.java`（登录成功后记审计——手动记录）
- 既有写操作控制器加 `@AuditLog` 注解（Tenant/Application/User/AiModel）

**前端新增：**
- `api/platform.ts`、`api/audit.ts`
- `components/ConfigForm.tsx`
- `pages/PlatformConfigPage.tsx`、`pages/TenantConfigPage.tsx`、`pages/SharedModelPage.tsx`、`pages/AuditLogPage.tsx`、`pages/PlatformOverviewPage.tsx`

**前端修改：**
- `App.tsx`（路由）、`layouts/MainLayout.tsx`（菜单）、`api/ai-model.ts`（AiModelConfig 加 `scope`）

---

# 里程碑 M1：统一配置中心（平台配置 + 租户配置）

产出：可读写的平台级/租户级键值配置，含后端接口与两个前端页面。

## Task 1: OwnerScope 枚举 + SecurityUtil

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/OwnerScope.java`
- Create: `backend/src/main/java/com/qingyu/care/util/SecurityUtil.java`

- [ ] **Step 1: 创建 OwnerScope 枚举**

```java
package com.qingyu.care.enums;

/**
 * 归属范围：平台级（共享/全局）或租户级
 */
public enum OwnerScope {
    /** 平台级：由平台管理员维护，tenant_id 为 null */
    PLATFORM,
    /** 租户级：归属具体租户 */
    TENANT
}
```

- [ ] **Step 2: 创建 SecurityUtil（角色判断与守卫）**

```java
package com.qingyu.care.util;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.enums.UserRole;

/**
 * 权限工具：读取当前登录用户角色并做守卫校验。
 * 角色写入方式与 TenantDataAspect 一致（Sa-Token Session 的 "role" 字段）。
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    /** 返回当前登录角色名，未登录或无角色返回 null */
    public static String getRole() {
        try {
            if (!StpUtil.isLogin()) return null;
            Object role = StpUtil.getSession().get("role");
            return role == null ? null : role.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPlatformAdmin() {
        return UserRole.PLATFORM_ADMIN.name().equals(getRole());
    }

    public static boolean isTenantAdmin() {
        return UserRole.TENANT_ADMIN.name().equals(getRole());
    }

    /** 要求平台管理员，否则抛异常（由 GlobalExceptionHandler 兜底为错误响应） */
    public static void requirePlatformAdmin() {
        if (!isPlatformAdmin()) {
            throw new RuntimeException("无权限：该操作仅限平台管理员");
        }
    }

    /** 要求租户管理员或平台管理员 */
    public static void requireTenantAdmin() {
        if (!isTenantAdmin() && !isPlatformAdmin()) {
            throw new RuntimeException("无权限：该操作仅限租户管理员");
        }
    }
}
```

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 2: SysConfig 实体与仓库

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/SysConfig.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/SysConfigRepository.java`

- [ ] **Step 1: 创建 SysConfig 实体**

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.OwnerScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 系统配置（键值）。scope 区分平台级(tenant_id 为 null)与租户级。
 * 不继承 BaseTenantEntity：租户隔离由 Service 显式按 scope+tenantId 控制。
 */
@Entity
@Table(name = "sys_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sys_config_scope_tenant_key",
                columnNames = {"scope", "tenant_id", "config_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerScope scope;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "config_value", length = 2000)
    private String configValue;

    @Column(length = 500)
    private String description;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 SysConfigRepository**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    // 平台级（tenant_id 为 null）
    List<SysConfig> findByScopeAndTenantIdIsNull(OwnerScope scope);
    Optional<SysConfig> findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope scope, String configKey);

    // 租户级
    List<SysConfig> findByScopeAndTenantId(OwnerScope scope, Long tenantId);
    Optional<SysConfig> findByScopeAndTenantIdAndConfigKey(OwnerScope scope, Long tenantId, String configKey);
}
```

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 3: ConfigService（TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/ConfigService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ConfigServiceTest.java`

- [ ] **Step 1: 先写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.SysConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock SysConfigRepository repository;
    @InjectMocks ConfigService service;

    @Test
    void getBoolean_returnsDefault_whenKeyMissing() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "platform.registration.enabled"))
                .thenReturn(Optional.empty());

        boolean v = service.getBoolean(OwnerScope.PLATFORM, null, "platform.registration.enabled", true);

        assertThat(v).isTrue();
    }

    @Test
    void getBoolean_parsesStoredValue() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "platform.registration.enabled"))
                .thenReturn(Optional.of(SysConfig.builder().configValue("false").build()));

        boolean v = service.getBoolean(OwnerScope.PLATFORM, null, "platform.registration.enabled", true);

        assertThat(v).isFalse();
    }

    @Test
    void getAll_platform_readsByTenantIdIsNull() {
        when(repository.findByScopeAndTenantIdIsNull(OwnerScope.PLATFORM))
                .thenReturn(List.of(
                        SysConfig.builder().configKey("a").configValue("1").build(),
                        SysConfig.builder().configKey("b").configValue("2").build()));

        var map = service.getAll(OwnerScope.PLATFORM, null);

        assertThat(map).containsEntry("a", "1").containsEntry("b", "2");
        verify(repository).findByScopeAndTenantIdIsNull(OwnerScope.PLATFORM);
    }

    @Test
    void getAll_tenant_readsByTenantId() {
        when(repository.findByScopeAndTenantId(OwnerScope.TENANT, 7L))
                .thenReturn(List.of(SysConfig.builder().configKey("x").configValue("y").build()));

        var map = service.getAll(OwnerScope.TENANT, 7L);

        assertThat(map).containsEntry("x", "y");
        verify(repository).findByScopeAndTenantId(OwnerScope.TENANT, 7L);
    }

    @Test
    void set_insertsNewConfig_whenAbsent() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "k"))
                .thenReturn(Optional.empty());

        service.set(OwnerScope.PLATFORM, null, "k", "v", "desc");

        verify(repository).save(argThat(c ->
                c.getScope() == OwnerScope.PLATFORM
                        && c.getTenantId() == null
                        && "k".equals(c.getConfigKey())
                        && "v".equals(c.getConfigValue())));
    }

    @Test
    void set_updatesExisting_whenPresent() {
        SysConfig existing = SysConfig.builder().id(3L).scope(OwnerScope.TENANT)
                .tenantId(5L).configKey("k").configValue("old").build();
        when(repository.findByScopeAndTenantIdAndConfigKey(OwnerScope.TENANT, 5L, "k"))
                .thenReturn(Optional.of(existing));

        service.set(OwnerScope.TENANT, 5L, "k", "new", null);

        verify(repository).save(argThat(c -> c.getId().equals(3L) && "new".equals(c.getConfigValue())));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ConfigServiceTest test`
Expected: 编译失败（ConfigService 不存在）

- [ ] **Step 3: 实现 ConfigService**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 统一配置读写服务。平台级传 tenantId=null；租户级传具体 tenantId。
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SysConfigRepository repository;

    public Map<String, String> getAll(OwnerScope scope, Long tenantId) {
        var list = (scope == OwnerScope.PLATFORM)
                ? repository.findByScopeAndTenantIdIsNull(scope)
                : repository.findByScopeAndTenantId(scope, tenantId);
        Map<String, String> map = new LinkedHashMap<>();
        list.forEach(c -> map.put(c.getConfigKey(), c.getConfigValue()));
        return map;
    }

    public String get(OwnerScope scope, Long tenantId, String key, String defaultValue) {
        Optional<SysConfig> found = (scope == OwnerScope.PLATFORM)
                ? repository.findByScopeAndConfigKeyAndTenantIdIsNull(scope, key)
                : repository.findByScopeAndTenantIdAndConfigKey(scope, tenantId, key);
        return found.map(SysConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultValue);
    }

    public boolean getBoolean(OwnerScope scope, Long tenantId, String key, boolean defaultValue) {
        String v = get(scope, tenantId, key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    public int getInt(OwnerScope scope, Long tenantId, String key, int defaultValue) {
        String v = get(scope, tenantId, key, null);
        try {
            return v == null ? defaultValue : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void set(OwnerScope scope, Long tenantId, String key, String value, String description) {
        Optional<SysConfig> found = (scope == OwnerScope.PLATFORM)
                ? repository.findByScopeAndConfigKeyAndTenantIdIsNull(scope, key)
                : repository.findByScopeAndTenantIdAndConfigKey(scope, tenantId, key);
        SysConfig config = found.orElseGet(() -> SysConfig.builder()
                .scope(scope)
                .tenantId(scope == OwnerScope.PLATFORM ? null : tenantId)
                .configKey(key)
                .build());
        config.setConfigValue(value);
        if (description != null) {
            config.setDescription(description);
        }
        repository.save(config);
    }

    @Transactional
    public void setAll(OwnerScope scope, Long tenantId, Map<String, String> values) {
        values.forEach((k, v) -> set(scope, tenantId, k, v, null));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ConfigServiceTest test`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: 检查点** — 上一步测试通过即为通过。

## Task 4: 配置接口（平台 + 租户）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/PlatformConfigController.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/TenantConfigController.java`

- [ ] **Step 1: 创建 PlatformConfigController**

```java
package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 平台级配置（仅平台管理员） */
@RestController
@RequestMapping("/v1/platform/config")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(configService.getAll(OwnerScope.PLATFORM, null));
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Map<String, String> values) {
        SecurityUtil.requirePlatformAdmin();
        configService.setAll(OwnerScope.PLATFORM, null, values);
        return ApiResponse.success("平台配置已更新", null);
    }
}
```

- [ ] **Step 2: 创建 TenantConfigController**

```java
package com.qingyu.care.controller;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 租户级配置（租户管理员，作用于当前登录租户） */
@RestController
@RequestMapping("/v1/tenant/config")
@RequiredArgsConstructor
public class TenantConfigController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        SecurityUtil.requireTenantAdmin();
        Long tenantId = requireTenantId();
        return ApiResponse.success(configService.getAll(OwnerScope.TENANT, tenantId));
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Map<String, String> values) {
        SecurityUtil.requireTenantAdmin();
        Long tenantId = requireTenantId();
        configService.setAll(OwnerScope.TENANT, tenantId, values);
        return ApiResponse.success("租户配置已更新", null);
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new RuntimeException("当前账号无所属租户，无法读写租户配置");
        }
        return tenantId;
    }
}
```

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 5: 前端 — 配置 api + 通用 ConfigForm + 两个配置页

**Files:**
- Create: `frontend/src/api/platform.ts`
- Create: `frontend/src/components/ConfigForm.tsx`
- Create: `frontend/src/pages/PlatformConfigPage.tsx`
- Create: `frontend/src/pages/TenantConfigPage.tsx`

- [ ] **Step 1: 创建 api/platform.ts（本里程碑仅用 config 部分，overview/models 在后续里程碑追加到同文件）**

```typescript
import api from './client';
import type { ApiResponse } from '../types';

export const platformService = {
  // 平台配置
  getPlatformConfig: (): Promise<ApiResponse<Record<string, string>>> =>
    api.get('/platform/config'),
  updatePlatformConfig: (values: Record<string, string>): Promise<ApiResponse<void>> =>
    api.put('/platform/config', values),

  // 租户配置
  getTenantConfig: (): Promise<ApiResponse<Record<string, string>>> =>
    api.get('/tenant/config'),
  updateTenantConfig: (values: Record<string, string>): Promise<ApiResponse<void>> =>
    api.put('/tenant/config', values),
};
```

- [ ] **Step 2: 创建通用 ConfigForm 组件（由字段 schema 驱动，平台/租户页复用）**

```tsx
import React, { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { useToast } from '@/hooks/use-toast';

export interface ConfigField {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'switch' | 'number';
  default: string;
  help?: string;
}

interface ConfigFormProps {
  title: string;
  fields: ConfigField[];
  load: () => Promise<{ data: Record<string, string> }>;
  save: (values: Record<string, string>) => Promise<{ message?: string }>;
}

const ConfigForm: React.FC<ConfigFormProps> = ({ title, fields, load, save }) => {
  const { toast } = useToast();
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    load()
      .then((res) => {
        const merged: Record<string, string> = {};
        fields.forEach((f) => {
          merged[f.key] = res.data?.[f.key] ?? f.default;
        });
        setValues(merged);
      })
      .catch((e) => toast({ title: '加载失败', description: e.message, variant: 'destructive' }))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setValue = (k: string, v: string) => setValues((prev) => ({ ...prev, [k]: v }));

  const handleSave = async () => {
    setSaving(true);
    try {
      const res = await save(values);
      toast({ title: res.message || '已保存' });
    } catch (e: any) {
      toast({ title: '保存失败', description: e.message, variant: 'destructive' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div className="p-6 text-muted-foreground">加载中…</div>;

  return (
    <div className="p-6 max-w-2xl space-y-6 overflow-y-auto h-full">
      <h3 className="text-lg font-semibold">{title}</h3>
      {fields.map((f) => (
        <div key={f.key} className="space-y-2">
          <label className="text-sm font-medium">{f.label}</label>
          {f.type === 'switch' ? (
            <div>
              <Switch
                checked={values[f.key] === 'true'}
                onCheckedChange={(c) => setValue(f.key, c ? 'true' : 'false')}
              />
            </div>
          ) : f.type === 'textarea' ? (
            <textarea
              className="w-full min-h-[100px] rounded-md border bg-background p-2 text-sm"
              value={values[f.key] ?? ''}
              onChange={(e) => setValue(f.key, e.target.value)}
            />
          ) : (
            <Input
              type={f.type === 'number' ? 'number' : 'text'}
              value={values[f.key] ?? ''}
              onChange={(e) => setValue(f.key, e.target.value)}
            />
          )}
          {f.help && <p className="text-xs text-muted-foreground">{f.help}</p>}
        </div>
      ))}
      <Button onClick={handleSave} disabled={saving}>
        {saving ? '保存中…' : '保存'}
      </Button>
    </div>
  );
};

export default ConfigForm;
```

> 说明：已核实现有 `components/ui/` 含 `button`、`input`、`switch`、`dialog`、`table`、`toast` 等；**不含 `label.tsx`**，故本计划统一用原生 `<label>` 元素，不引用 Label 组件。`use-toast` 位于 `@/hooks/use-toast`。

- [ ] **Step 3: 创建 PlatformConfigPage**

```tsx
import React from 'react';
import ConfigForm, { ConfigField } from '@/components/ConfigForm';
import { platformService } from '@/api/platform';

const FIELDS: ConfigField[] = [
  { key: 'platform.registration.enabled', label: '开放注册', type: 'switch', default: 'true', help: '关闭后新用户无法自助注册' },
  { key: 'platform.default.plan', label: '新租户默认套餐', type: 'text', default: 'FREE', help: '取值需为 TenantPlan 枚举名，如 FREE' },
  { key: 'platform.announcement', label: '系统公告', type: 'textarea', default: '', help: '展示给所有租户的公告文本' },
];

const PlatformConfigPage: React.FC = () => (
  <ConfigForm
    title="平台配置"
    fields={FIELDS}
    load={platformService.getPlatformConfig}
    save={platformService.updatePlatformConfig}
  />
);

export default PlatformConfigPage;
```

- [ ] **Step 4: 创建 TenantConfigPage**

```tsx
import React from 'react';
import ConfigForm, { ConfigField } from '@/components/ConfigForm';
import { platformService } from '@/api/platform';

const FIELDS: ConfigField[] = [
  { key: 'tenant.display.name', label: '对外显示名 / 品牌', type: 'text', default: '' },
  { key: 'tenant.session.timeout.minutes', label: '会话超时（分钟）', type: 'number', default: '', help: '留空则使用全局默认' },
  { key: 'tenant.default.model.scenario', label: '默认模型场景', type: 'text', default: 'default' },
];

const TenantConfigPage: React.FC = () => (
  <ConfigForm
    title="租户配置"
    fields={FIELDS}
    load={platformService.getTenantConfig}
    save={platformService.updateTenantConfig}
  />
);

export default TenantConfigPage;
```

- [ ] **Step 5: 检查点 — 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功（本 Task 未接路由，页面暂未挂载，M4 统一接入）

## Task 6: 让平台配置真正生效 — 注册开关拦截

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/AuthService.java`（`register` 方法开头）

- [ ] **Step 1: 在 AuthService.register 开头加注册开关校验**

在 `register(...)` 方法体最前面插入（注入 `ConfigService configService` 到该类的构造字段——因用 `@RequiredArgsConstructor`，加一个 `private final ConfigService configService;` 字段即可）：

```java
// 平台级"开放注册"开关：默认开启
if (!configService.getBoolean(com.qingyu.care.enums.OwnerScope.PLATFORM, null,
        "platform.registration.enabled", true)) {
    throw new RuntimeException("平台已关闭自助注册，请联系管理员");
}
```

- [ ] **Step 2: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

> M1 结束。此时配置中心后端可用、前端页面就绪（待 M4 接菜单/路由）、注册开关已消费。

---

# 里程碑 M2：共享模型池

产出：`ai_model_config` 支持平台共享模型；路由取「租户自有 ∪ 平台共享」；超管专属共享模型 CRUD + 前端页。

## Task 7: AiModelConfig 增加 scope 字段 + 仓库原生查询

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/AiModelConfig.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/AiModelConfigRepository.java`

- [ ] **Step 1: 给 AiModelConfig 加 scope 字段**

在 `remark` 字段之后、`createdAt` 之前插入：

```java
    /**
     * 归属范围：TENANT=租户/应用自有；PLATFORM=平台共享模型
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private com.qingyu.care.enums.OwnerScope scope = com.qingyu.care.enums.OwnerScope.TENANT;
```

> 兼容性：旧行 `scope` 为 NULL，将被视为租户自有（合并逻辑只把 `scope='PLATFORM'` 当共享，其余走租户过滤）。Task 9 的 `init()` 会把默认模型置为 PLATFORM。

- [ ] **Step 2: 给 AiModelConfigRepository 加原生共享查询**

在接口内追加（注意 import `org.springframework.data.jpa.repository.Query` 与 `org.springframework.data.repository.query.Param`）：

```java
    /**
     * 查询启用的平台共享模型（scope=PLATFORM），按优先级排序。
     * 使用原生 SQL，绕过 Hibernate 的租户过滤器（@Filter 不作用于原生查询），
     * 从而让租户用户也能命中平台共享模型。
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM ai_model_config " +
                "WHERE scope = 'PLATFORM' AND enabled = true AND scenario = :scenario " +
                "ORDER BY priority ASC",
        nativeQuery = true)
    List<AiModelConfig> findSharedModelsByScenario(
        @org.springframework.data.repository.query.Param("scenario") String scenario);

    /** 查询所有平台共享模型（超管管理用） */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM ai_model_config WHERE scope = 'PLATFORM' ORDER BY scenario ASC, priority ASC",
        nativeQuery = true)
    List<AiModelConfig> findAllSharedModels();
```

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 8: ModelRouterService 合并路由（TDD）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ModelRouterServiceMergeTest.java`

- [ ] **Step 1: 先写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRouterServiceMergeTest {

    @Mock AiModelConfigRepository repository;
    @InjectMocks ModelRouterService service;

    private AiModelConfig model(String name, String provider, String modelId, int priority, OwnerScope scope) {
        return AiModelConfig.builder()
                .name(name).provider(provider).modelId(modelId)
                .endpoint("http://x").scenario("default").priority(priority)
                .enabled(true).scope(scope).build();
    }

    @Test
    void mergesTenantOwnBeforeShared() {
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of(model("own", "openai", "gpt-a", 1, OwnerScope.TENANT)));
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(model("shared", "dashscope", "qwen", 1, OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("default");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("own", "shared");
    }

    @Test
    void dedupsByProviderModelEndpoint_keepingTenantOwn() {
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of(model("own", "openai", "gpt-a", 1, OwnerScope.TENANT)));
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(model("shared-dup", "openai", "gpt-a", 1, OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("default");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("own");
    }

    @Test
    void fallsBackToDefaultScenario_whenScenarioEmpty() {
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("complaint"))
                .thenReturn(List.of());
        when(repository.findSharedModelsByScenario("complaint"))
                .thenReturn(List.of());
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of());
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(model("shared-default", "dashscope", "qwen", 1, OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("complaint");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("shared-default");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ModelRouterServiceMergeTest test`
Expected: FAIL（当前 `getModelsForScenario` 未合并共享模型，且未调用 `findSharedModelsByScenario`）

- [ ] **Step 3: 替换 getModelsForScenario 实现并新增私有合并方法**

将 `ModelRouterService` 中现有的 `getModelsForScenario` 方法整体替换为：

```java
    /**
     * 获取指定场景的可用模型：租户自有 ∪ 平台共享，租户自有优先，
     * 按 (provider, modelId, endpoint) 去重；为空则降级到 default 场景。
     */
    public List<AiModelConfig> getModelsForScenario(String scenario) {
        List<AiModelConfig> merged = mergeTenantAndShared(scenario);
        if (merged.isEmpty() && !"default".equals(scenario)) {
            merged = mergeTenantAndShared("default");
        }
        return merged;
    }

    private List<AiModelConfig> mergeTenantAndShared(String scenario) {
        List<AiModelConfig> own = repository.findByScenarioAndEnabledTrueOrderByPriorityAsc(scenario);
        List<AiModelConfig> shared = repository.findSharedModelsByScenario(scenario);
        java.util.LinkedHashMap<String, AiModelConfig> byKey = new java.util.LinkedHashMap<>();
        for (AiModelConfig m : own) byKey.putIfAbsent(keyOf(m), m);
        for (AiModelConfig m : shared) byKey.putIfAbsent(keyOf(m), m);
        return new java.util.ArrayList<>(byKey.values());
    }

    private String keyOf(AiModelConfig m) {
        return m.getProvider() + "|" + m.getModelId() + "|" + m.getEndpoint();
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ModelRouterServiceMergeTest test`
Expected: Tests run: 3, Failures: 0

## Task 9: 共享模型 init 改造 + 超管 CRUD 服务方法

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`

- [ ] **Step 1: 修改 init()，默认模型标记为 PLATFORM 共享**

将 `init()` 中 `AiModelConfig.builder()...` 链里追加 `.scope(com.qingyu.care.enums.OwnerScope.PLATFORM)`，并把 remark 改为 `"从 application.yml 初始化的平台共享默认模型"`：

```java
            repository.save(AiModelConfig.builder()
                    .name("默认模型")
                    .provider("openai")
                    .modelId(defaultModel)
                    .endpoint(defaultBaseUrl)
                    .apiKey(defaultApiKey)
                    .scenario("default")
                    .priority(1)
                    .enabled(true)
                    .scope(com.qingyu.care.enums.OwnerScope.PLATFORM)
                    .remark("从 application.yml 初始化的平台共享默认模型")
                    .build());
```

- [ ] **Step 2: 追加共享模型 CRUD 方法（强制 scope=PLATFORM、tenantId=null）**

在 `ModelRouterService` 内追加：

```java
    /** 列出全部平台共享模型 */
    public List<AiModelConfig> getSharedModels() {
        return repository.findAllSharedModels();
    }

    /** 保存平台共享模型（强制平台归属，避免被 TenantEntityListener 填充租户） */
    @Transactional
    public AiModelConfig saveSharedModel(AiModelConfig config) {
        config.setScope(com.qingyu.care.enums.OwnerScope.PLATFORM);
        config.setTenantId(null);
        config.setAppId(null);
        return repository.save(config);
    }
```

- [ ] **Step 3: 检查点 — 编译 + 回归已写测试**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ModelRouterServiceMergeTest test`
Expected: Tests run: 3, Failures: 0

## Task 10: 共享模型池接口（超管）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/PlatformController.java`

> 说明：本控制器同时承载 M2 的共享模型 CRUD 与 M3 的健康概览（`/v1/platform` 下）。本 Task 只加共享模型端点，M3 再补 `/overview`。

- [ ] **Step 1: 创建 PlatformController（共享模型部分）**

```java
package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 平台级管理（仅平台管理员）：共享模型池、系统概览 */
@RestController
@RequestMapping("/v1/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final ModelRouterService modelRouterService;

    @GetMapping("/models")
    public ApiResponse<List<AiModelConfig>> listSharedModels() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(modelRouterService.getSharedModels());
    }

    @PostMapping("/models")
    public ApiResponse<AiModelConfig> createSharedModel(@RequestBody AiModelConfig config) {
        SecurityUtil.requirePlatformAdmin();
        config.setId(null);
        return ApiResponse.success("共享模型已创建", modelRouterService.saveSharedModel(config));
    }

    @PutMapping("/models/{id}")
    public ApiResponse<AiModelConfig> updateSharedModel(@PathVariable Long id, @RequestBody AiModelConfig config) {
        SecurityUtil.requirePlatformAdmin();
        AiModelConfig existing = modelRouterService.getById(id);
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getModelId() != null) existing.setModelId(config.getModelId());
        if (config.getEndpoint() != null) existing.setEndpoint(config.getEndpoint());
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) existing.setApiKey(config.getApiKey());
        if (config.getScenario() != null) existing.setScenario(config.getScenario());
        if (config.getPriority() != null) existing.setPriority(config.getPriority());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getRemark() != null) existing.setRemark(config.getRemark());
        return ApiResponse.success("共享模型已更新", modelRouterService.saveSharedModel(existing));
    }

    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteSharedModel(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        modelRouterService.deleteModel(id);
        return ApiResponse.success("共享模型已删除", null);
    }
}
```

- [ ] **Step 2: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 11: 前端 — 共享模型 api + SharedModelPage

**Files:**
- Modify: `frontend/src/api/ai-model.ts`（AiModelConfig 类型加 `scope`）
- Modify: `frontend/src/api/platform.ts`（追加共享模型方法）
- Create: `frontend/src/pages/SharedModelPage.tsx`

- [ ] **Step 1: ai-model.ts 的 AiModelConfig 接口加字段**

在 `AiModelConfig` 接口 `remark` 后加：

```typescript
    scope?: 'PLATFORM' | 'TENANT';
```

- [ ] **Step 2: platform.ts 追加共享模型方法（对象内追加）**

```typescript
  // 共享模型池
  listSharedModels: (): Promise<ApiResponse<import('./ai-model').AiModelConfig[]>> =>
    api.get('/platform/models'),
  createSharedModel: (data: Partial<import('./ai-model').AiModelConfig>): Promise<ApiResponse<import('./ai-model').AiModelConfig>> =>
    api.post('/platform/models', data),
  updateSharedModel: (id: number, data: Partial<import('./ai-model').AiModelConfig>): Promise<ApiResponse<import('./ai-model').AiModelConfig>> =>
    api.put(`/platform/models/${id}`, data),
  deleteSharedModel: (id: number): Promise<ApiResponse<void>> =>
    api.delete(`/platform/models/${id}`),
```

- [ ] **Step 3: 创建 SharedModelPage（表格 + 新增/编辑对话框 + 删除）**

```tsx
import React, { useEffect, useState } from 'react';
import { platformService } from '@/api/platform';
import type { AiModelConfig } from '@/api/ai-model';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogTrigger,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';

const empty: Partial<AiModelConfig> = {
  name: '', provider: 'dashscope', modelId: '', endpoint: '', apiKey: '',
  scenario: 'default', priority: 100, enabled: true,
};

const SharedModelPage: React.FC = () => {
  const { toast } = useToast();
  const [list, setList] = useState<AiModelConfig[]>([]);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<Partial<AiModelConfig>>(empty);

  const reload = () =>
    platformService.listSharedModels().then((r) => setList(r.data)).catch((e) =>
      toast({ title: '加载失败', description: e.message, variant: 'destructive' }));

  useEffect(() => { reload(); /* eslint-disable-next-line */ }, []);

  const set = (k: keyof AiModelConfig, v: any) => setForm((p) => ({ ...p, [k]: v }));

  const submit = async () => {
    try {
      if (form.id) await platformService.updateSharedModel(form.id, form);
      else await platformService.createSharedModel(form);
      toast({ title: '已保存' });
      setOpen(false); setForm(empty); reload();
    } catch (e: any) {
      toast({ title: '保存失败', description: e.message, variant: 'destructive' });
    }
  };

  const remove = async (id: number) => {
    try { await platformService.deleteSharedModel(id); reload(); }
    catch (e: any) { toast({ title: '删除失败', description: e.message, variant: 'destructive' }); }
  };

  return (
    <div className="p-6 space-y-4 overflow-y-auto h-full">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">共享模型池</h3>
        <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) setForm(empty); }}>
          <DialogTrigger asChild><Button onClick={() => setForm(empty)}>新增共享模型</Button></DialogTrigger>
          <DialogContent>
            <DialogHeader><DialogTitle>{form.id ? '编辑' : '新增'}共享模型</DialogTitle></DialogHeader>
            <div className="space-y-3">
              {[
                ['name', '名称'], ['provider', '提供商'], ['modelId', '模型ID'],
                ['endpoint', '端点(可选)'], ['apiKey', 'API Key'], ['scenario', '场景'],
              ].map(([k, label]) => (
                <div key={k} className="space-y-1">
                  <label className="text-sm font-medium">{label}</label>
                  <Input value={(form as any)[k] ?? ''} onChange={(e) => set(k as keyof AiModelConfig, e.target.value)} />
                </div>
              ))}
              <div className="space-y-1">
                <label className="text-sm font-medium">优先级</label>
                <Input type="number" value={form.priority ?? 100}
                  onChange={(e) => set('priority', Number(e.target.value))} />
              </div>
            </div>
            <DialogFooter><Button onClick={submit}>保存</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      </div>

      <table className="w-full text-sm border rounded-md overflow-hidden">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left p-2">名称</th><th className="text-left p-2">提供商</th>
            <th className="text-left p-2">模型ID</th><th className="text-left p-2">场景</th>
            <th className="text-left p-2">优先级</th><th className="text-left p-2">状态</th>
            <th className="text-right p-2">操作</th>
          </tr>
        </thead>
        <tbody>
          {list.map((m) => (
            <tr key={m.id} className="border-t">
              <td className="p-2">{m.name}</td><td className="p-2">{m.provider}</td>
              <td className="p-2">{m.modelId}</td><td className="p-2">{m.scenario}</td>
              <td className="p-2">{m.priority}</td>
              <td className="p-2">{m.enabled ? '启用' : '停用'}</td>
              <td className="p-2 text-right space-x-2">
                <Button variant="outline" size="sm" onClick={() => { setForm(m); setOpen(true); }}>编辑</Button>
                <Button variant="destructive" size="sm" onClick={() => remove(m.id)}>删除</Button>
              </td>
            </tr>
          ))}
          {list.length === 0 && (
            <tr><td colSpan={7} className="p-4 text-center text-muted-foreground">暂无共享模型</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
};

export default SharedModelPage;
```

- [ ] **Step 4: 检查点 — 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功

> M2 结束。共享模型池后端可用、路由已合并、前端页就绪（待 M4 接菜单/路由）。

---

# 里程碑 M3：审计日志 + 健康概览

产出：`@AuditLog` 注解 + AOP 切面异步落库；审计查询接口与页面；系统健康概览接口与页面。

## Task 12: SysAuditLog 实体与仓库

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/SysAuditLog.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/SysAuditLogRepository.java`

- [ ] **Step 1: 创建 SysAuditLog 实体**

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 操作审计日志。不继承 BaseTenantEntity：跨租户查询由 Service 显式控制。
 */
@Entity
@Table(name = "sys_audit_log", indexes = {
        @Index(name = "idx_audit_tenant", columnList = "tenant_id"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 100)
    private String username;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 50)
    private String targetId;

    @Column(length = 1000)
    private String detail;

    @Column(length = 64)
    private String ip;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 创建 SysAuditLogRepository（支持分页 + 动态过滤）**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.SysAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface SysAuditLogRepository
        extends JpaRepository<SysAuditLog, Long>, JpaSpecificationExecutor<SysAuditLog> {
}
```

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 13: @AuditLog 注解 + AuditLogService（含异步落库与脱敏）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/annotation/AuditLog.java`
- Create: `backend/src/main/java/com/qingyu/care/service/AuditLogService.java`

- [ ] **Step 1: 创建 @AuditLog 注解**

```java
package com.qingyu.care.annotation;

import java.lang.annotation.*;

/**
 * 标注在需要记录操作审计的 Controller 方法上。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {
    /** 动作，如 TENANT_CREATE、MODEL_UPDATE */
    String action();
    /** 目标类型，如 Tenant、AiModelConfig */
    String targetType() default "";
}
```

- [ ] **Step 2: 创建 AuditLogService（异步保存）**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.repository.SysAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final SysAuditLogRepository repository;

    /** 异步落库；任何异常不得影响主流程 */
    @Async
    public void saveAsync(SysAuditLog log) {
        try {
            repository.save(log);
        } catch (Exception e) {
            AuditLogService.log.warn("写审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 分页查询。platformAdmin=true 查全部；否则仅本租户。
     * action/username 为空则不过滤。
     */
    public Page<SysAuditLog> query(boolean platformAdmin, Long tenantId,
                                   String action, String username, Pageable pageable) {
        Specification<SysAuditLog> spec = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (!platformAdmin) {
                ps.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (action != null && !action.isBlank()) {
                ps.add(cb.equal(root.get("action"), action));
            }
            if (username != null && !username.isBlank()) {
                ps.add(cb.like(root.get("username"), "%" + username + "%"));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return repository.findAll(spec, pageable);
    }

    /** 用于测试/说明的时间点占位（非业务字段） */
    public LocalDateTime now() { return LocalDateTime.now(); }
}
```

> `@Async` 生效依赖已存在的 `@EnableAsync`（`AsyncConfig`）。`saveAsync` 与 `SysAuditLog` 不经过租户过滤（实体未继承 BaseTenantEntity），insert 不受影响。

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 14: AuditLogAspect（TDD 核心逻辑：脱敏 + 组装）

采集切面涉及请求上下文，难以纯单测；将**可测的纯逻辑**（脱敏）抽成静态方法先 TDD，再写切面装配。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/AuditLogAspect.java`
- Test: `backend/src/test/java/com/qingyu/care/config/AuditLogSanitizeTest.java`

- [ ] **Step 1: 先写失败测试（脱敏逻辑）**

```java
package com.qingyu.care.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuditLogSanitizeTest {

    @Test
    void masksApiKeyAndPassword() {
        String detail = AuditLogAspect.sanitize(
                "{name=x, apiKey=sk-secret-123, password=abc, priority=1}");
        assertThat(detail).doesNotContain("sk-secret-123");
        assertThat(detail).doesNotContain("abc");
        assertThat(detail).contains("name=x");
        assertThat(detail).contains("priority=1");
    }

    @Test
    void truncatesToMaxLength() {
        String big = "x".repeat(2000);
        assertThat(AuditLogAspect.sanitize(big).length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void handlesNull() {
        assertThat(AuditLogAspect.sanitize(null)).isEqualTo("");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=AuditLogSanitizeTest test`
Expected: 编译失败（AuditLogAspect 不存在）

- [ ] **Step 3: 实现 AuditLogAspect**

```java
package com.qingyu.care.config;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.entity.User;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 审计切面：拦截标注 @AuditLog 的方法，在请求线程内组装日志后异步落库。
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    // 脱敏：匹配 apiKey=... / password=... / secret=... （到下一个逗号或右括号）
    private static final Pattern SENSITIVE =
            Pattern.compile("(?i)(apikey|password|secret|appsecret)=([^,}\\]]*)");
    private static final int MAX_DETAIL = 1000;

    /** 纯函数：脱敏 + 截断。可独立单测。 */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String masked = SENSITIVE.matcher(raw).replaceAll("$1=***");
        return masked.length() > MAX_DETAIL ? masked.substring(0, MAX_DETAIL) : masked;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        Object result = pjp.proceed(); // 先执行业务，成功后再记录
        try {
            SysAuditLog record = build(pjp, auditLog);
            auditLogService.saveAsync(record);
        } catch (Exception e) {
            log.warn("组装审计日志失败: {}", e.getMessage());
        }
        return result;
    }

    private SysAuditLog build(ProceedingJoinPoint pjp, AuditLog auditLog) {
        Long userId = null;
        String username = null;
        Long tenantId = TenantContext.getTenantId();
        try {
            if (StpUtil.isLogin()) {
                userId = StpUtil.getLoginIdAsLong();
                Long uid = userId;
                username = userRepository.findById(uid).map(User::getUsername).orElse("user#" + uid);
            }
        } catch (Exception ignored) { }

        return SysAuditLog.builder()
                .tenantId(tenantId)
                .userId(userId)
                .username(username)
                .action(auditLog.action())
                .targetType(auditLog.targetType())
                .targetId(extractTargetId(pjp))
                .detail(sanitize(Arrays.toString(pjp.getArgs())))
                .ip(clientIp())
                .build();
    }

    /** 约定：若方法首个参数为 Long/String（通常是 @PathVariable id），取之作为 targetId */
    private String extractTargetId(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args.length > 0 && (args[0] instanceof Long || args[0] instanceof String)) {
            return String.valueOf(args[0]);
        }
        return null;
    }

    private String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            var req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
```

> `UserRepository.findById` 在请求线程执行：平台管理员无租户过滤；租户用户查自身记录命中其租户过滤。若失败降级为 `user#<id>`，不抛出。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=AuditLogSanitizeTest test`
Expected: Tests run: 3, Failures: 0

## Task 15: 审计查询接口 + 登录审计

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/AuditLogController.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/AuthService.java`（登录成功后手动记审计）

- [ ] **Step 1: 创建 AuditLogController**

```java
package com.qingyu.care.controller;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.PageDTO;
import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.service.AuditLogService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/** 审计日志查询：超管全量，租户管理员限本租户 */
@RestController
@RequestMapping("/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<PageDTO<SysAuditLog>> query(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SecurityUtil.requireTenantAdmin(); // 租户管理员或平台管理员
        boolean platformAdmin = SecurityUtil.isPlatformAdmin();
        Long tenantId = TenantContext.getTenantId();
        Page<SysAuditLog> result = auditLogService.query(
                platformAdmin, tenantId, action, username,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResponse.success(PageDTO.from(result));
    }
}
```

- [ ] **Step 2: 在 AuthService.login 成功后手动记登录审计**

在 `login(...)` 返回 `LoginResponse` 之前（`StpUtil.login(...)` 之后、用户已确认存在处）插入。需给 `AuthService` 加字段 `private final AuditLogService auditLogService;`（`@RequiredArgsConstructor`）。用已查到的 `User user` 对象组装：

```java
auditLogService.saveAsync(com.qingyu.care.entity.SysAuditLog.builder()
        .tenantId(user.getTenant() != null ? user.getTenant().getId() : null)
        .userId(user.getId())
        .username(user.getUsername())
        .action("LOGIN")
        .targetType("User")
        .targetId(String.valueOf(user.getId()))
        .build());
```

> 若 `login` 内变量名不同（如 `existingUser`），按实际变量名调整。

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 16: 给关键写操作挂 @AuditLog 注解

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/TenantController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ApplicationController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/UserController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/AiModelController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/PlatformController.java`

- [ ] **Step 1: 在各写操作方法上加注解**

先 `import com.qingyu.care.annotation.AuditLog;`。在对应方法上加注解（示例，按各控制器实际方法名对齐）：

- TenantController：`@AuditLog(action = "TENANT_CREATE", targetType = "Tenant")` / `TENANT_UPDATE` / `TENANT_DELETE`
- ApplicationController：`APP_CREATE` / `APP_UPDATE` / `APP_DELETE`，`targetType = "Application"`
- UserController：`USER_CREATE` / `USER_UPDATE` / `USER_DELETE`，`targetType = "User"`
- AiModelController：`MODEL_CREATE` / `MODEL_UPDATE` / `MODEL_DELETE`，`targetType = "AiModelConfig"`
- PlatformController（共享模型）：`SHARED_MODEL_CREATE` / `SHARED_MODEL_UPDATE` / `SHARED_MODEL_DELETE`

例（AiModelController.createModel 上方）：

```java
    @PostMapping
    @com.qingyu.care.annotation.AuditLog(action = "MODEL_CREATE", targetType = "AiModelConfig")
    public ApiResponse<AiModelConfig> createModel(@RequestBody AiModelConfig config) {
```

> 只标注「增/改/删」写操作，不标注查询。

- [ ] **Step 2: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 17: 健康概览接口

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/PlatformOverviewDTO.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/PlatformController.java`（加 `/overview`）

- [ ] **Step 1: 创建 PlatformOverviewDTO**

```java
package com.qingyu.care.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlatformOverviewDTO {
    private String health;          // UP / DOWN / UNKNOWN
    private long tenantCount;
    private long applicationCount;
    private long userCount;
    private long sessionCount;
    private long todaySessionCount;
}
```

- [ ] **Step 2: 在 PlatformController 注入依赖并加 /overview**

给 `PlatformController` 追加构造字段（`@RequiredArgsConstructor` 自动注入）：

```java
    private final org.springframework.boot.actuate.health.HealthEndpoint healthEndpoint;
    private final com.qingyu.care.repository.TenantRepository tenantRepository;
    private final com.qingyu.care.repository.ApplicationRepository applicationRepository;
    private final com.qingyu.care.repository.UserRepository userRepository;
    private final com.qingyu.care.repository.ChatSessionRepository sessionRepository;
```

加方法：

```java
    @GetMapping("/overview")
    public ApiResponse<com.qingyu.care.dto.PlatformOverviewDTO> overview() {
        SecurityUtil.requirePlatformAdmin();
        String health;
        try {
            health = healthEndpoint.health().getStatus().getCode();
        } catch (Exception e) {
            health = "UNKNOWN";
        }
        java.time.LocalDateTime startOfToday =
                java.time.LocalDateTime.of(java.time.LocalDate.now(), java.time.LocalTime.MIN);
        long todaySessions;
        try {
            todaySessions = sessionRepository.countTodaySessions(startOfToday);
        } catch (Exception e) {
            todaySessions = 0L;
        }
        return ApiResponse.success(com.qingyu.care.dto.PlatformOverviewDTO.builder()
                .health(health)
                .tenantCount(tenantRepository.count())
                .applicationCount(applicationRepository.count())
                .userCount(userRepository.count())
                .sessionCount(sessionRepository.count())
                .todaySessionCount(todaySessions)
                .build());
    }
```

> `countTodaySessions(LocalDateTime)` 在 `ChatSessionRepository` 已存在（`DashboardController` 使用过）。`HealthEndpoint` 由 actuator 自动装配可注入。`TenantRepository`/`ApplicationRepository` 若类名不同，按实际存在的 repository 调整（`ls repository/` 确认）。

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 18: 前端 — 审计 api/页面 + 概览 api/页面

**Files:**
- Create: `frontend/src/api/audit.ts`
- Modify: `frontend/src/api/platform.ts`（追加 overview）
- Create: `frontend/src/pages/AuditLogPage.tsx`
- Create: `frontend/src/pages/PlatformOverviewPage.tsx`

- [ ] **Step 1: 创建 api/audit.ts**

```typescript
import api from './client';
import type { ApiResponse } from '../types';

export interface AuditLog {
  id: number;
  tenantId: number | null;
  userId: number | null;
  username: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: string | null;
  ip: string | null;
  createdAt: string;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export const auditService = {
  query: (params: { action?: string; username?: string; page?: number; size?: number }):
    Promise<ApiResponse<PageResult<AuditLog>>> =>
    api.get('/audit-logs', { params }),
};
```

- [ ] **Step 2: platform.ts 追加 overview 方法与类型**

```typescript
export interface PlatformOverview {
  health: string;
  tenantCount: number;
  applicationCount: number;
  userCount: number;
  sessionCount: number;
  todaySessionCount: number;
}
```

并在 `platformService` 对象内追加：

```typescript
  getOverview: (): Promise<ApiResponse<PlatformOverview>> => api.get('/platform/overview'),
```

- [ ] **Step 3: 创建 AuditLogPage（表格 + 分页 + action 过滤）**

```tsx
import React, { useEffect, useState } from 'react';
import { auditService, type AuditLog } from '@/api/audit';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useToast } from '@/hooks/use-toast';

const AuditLogPage: React.FC = () => {
  const { toast } = useToast();
  const [rows, setRows] = useState<AuditLog[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [action, setAction] = useState('');

  const load = (p = page) => {
    auditService.query({ action: action || undefined, page: p, size: 20 })
      .then((r) => { setRows(r.data.content); setTotalPages(r.data.totalPages); setPage(r.data.number); })
      .catch((e) => toast({ title: '加载失败', description: e.message, variant: 'destructive' }));
  };

  useEffect(() => { load(0); /* eslint-disable-next-line */ }, []);

  return (
    <div className="p-6 space-y-4 overflow-y-auto h-full">
      <div className="flex items-center gap-2">
        <h3 className="text-lg font-semibold mr-auto">审计日志</h3>
        <Input placeholder="按 action 过滤" value={action}
          onChange={(e) => setAction(e.target.value)} className="w-48" />
        <Button onClick={() => load(0)}>查询</Button>
      </div>
      <table className="w-full text-sm border rounded-md overflow-hidden">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left p-2">时间</th><th className="text-left p-2">操作者</th>
            <th className="text-left p-2">动作</th><th className="text-left p-2">目标</th>
            <th className="text-left p-2">IP</th><th className="text-left p-2">详情</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className="border-t align-top">
              <td className="p-2 whitespace-nowrap">{new Date(r.createdAt).toLocaleString()}</td>
              <td className="p-2">{r.username ?? r.userId ?? '-'}</td>
              <td className="p-2">{r.action}</td>
              <td className="p-2">{r.targetType}{r.targetId ? `#${r.targetId}` : ''}</td>
              <td className="p-2">{r.ip ?? '-'}</td>
              <td className="p-2 max-w-xs truncate" title={r.detail ?? ''}>{r.detail}</td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr><td colSpan={6} className="p-4 text-center text-muted-foreground">暂无记录</td></tr>
          )}
        </tbody>
      </table>
      <div className="flex items-center gap-2 justify-end">
        <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => load(page - 1)}>上一页</Button>
        <span className="text-sm text-muted-foreground">{page + 1} / {Math.max(totalPages, 1)}</span>
        <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => load(page + 1)}>下一页</Button>
      </div>
    </div>
  );
};

export default AuditLogPage;
```

- [ ] **Step 4: 创建 PlatformOverviewPage（卡片）**

```tsx
import React, { useEffect, useState } from 'react';
import { platformService, type PlatformOverview } from '@/api/platform';
import { useToast } from '@/hooks/use-toast';

const Card: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="rounded-lg border p-4 bg-muted/20">
    <p className="text-xs text-muted-foreground">{label}</p>
    <p className="text-2xl font-bold mt-1">{value}</p>
  </div>
);

const PlatformOverviewPage: React.FC = () => {
  const { toast } = useToast();
  const [data, setData] = useState<PlatformOverview | null>(null);

  useEffect(() => {
    platformService.getOverview().then((r) => setData(r.data))
      .catch((e) => toast({ title: '加载失败', description: e.message, variant: 'destructive' }));
    // eslint-disable-next-line
  }, []);

  if (!data) return <div className="p-6 text-muted-foreground">加载中…</div>;

  return (
    <div className="p-6 space-y-4 overflow-y-auto h-full">
      <h3 className="text-lg font-semibold">系统概览</h3>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <Card label="系统健康" value={
          <span className={data.health === 'UP' ? 'text-green-600' : 'text-red-600'}>{data.health}</span>} />
        <Card label="租户数" value={data.tenantCount} />
        <Card label="应用数" value={data.applicationCount} />
        <Card label="用户数" value={data.userCount} />
        <Card label="会话总数" value={data.sessionCount} />
        <Card label="今日对话" value={data.todaySessionCount} />
      </div>
    </div>
  );
};

export default PlatformOverviewPage;
```

- [ ] **Step 5: 检查点 — 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功

> M3 结束。审计与概览后端可用、前端页就绪。

---

# 里程碑 M4：菜单与路由整合 + 端到端验证

## Task 19: 接入前端路由与菜单

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

- [ ] **Step 1: App.tsx 追加 import 与路由**

在其余 page import 处追加：

```tsx
import PlatformOverviewPage from './pages/PlatformOverviewPage';
import SharedModelPage from './pages/SharedModelPage';
import PlatformConfigPage from './pages/PlatformConfigPage';
import TenantConfigPage from './pages/TenantConfigPage';
import AuditLogPage from './pages/AuditLogPage';
```

在 `{/* 管理 */}` 那组 Route 内追加（这些页面不需要选中应用，故不包 `RequireApp`）：

```tsx
          <Route path="platform/overview" element={<PlatformOverviewPage />} />
          <Route path="platform/models" element={<SharedModelPage />} />
          <Route path="platform/config" element={<PlatformConfigPage />} />
          <Route path="tenant/config" element={<TenantConfigPage />} />
          <Route path="audit-logs" element={<AuditLogPage />} />
```

- [ ] **Step 2: MainLayout.tsx 追加菜单项**

先在 lucide import 里补充图标（如 `Activity, Boxes, SlidersHorizontal, ScrollText, Building2`）。在 `title: '管理'` 的 `items` 数组内追加：

```tsx
            { label: '系统概览', path: '/platform/overview', icon: Activity, roles: ['PLATFORM_ADMIN'] },
            { label: '共享模型池', path: '/platform/models', icon: Boxes, roles: ['PLATFORM_ADMIN'] },
            { label: '平台配置', path: '/platform/config', icon: SlidersHorizontal, roles: ['PLATFORM_ADMIN'] },
            { label: '审计日志', path: '/audit-logs', icon: ScrollText, roles: ['PLATFORM_ADMIN', 'TENANT_ADMIN'] },
            { label: '租户配置', path: '/tenant/config', icon: Building2, roles: ['TENANT_ADMIN'] },
```

> 图标名以 lucide-react 实际导出为准；若某图标不存在，换用已 import 的现有图标（如 `Settings`、`Shield`）。

- [ ] **Step 3: 检查点 — 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功

## Task 20: 全量回归 + 端到端手测

- [ ] **Step 1: 后端全量编译 + 新单测**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ConfigServiceTest,ModelRouterServiceMergeTest,AuditLogSanitizeTest test`
Expected: 三个测试类全部通过（合计 12 个测试）

- [ ] **Step 2: 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功，无类型错误

- [ ] **Step 3: 启动手测（需本地 DB/依赖，由用户执行）**

启动后逐项验证：
1. 以平台管理员登录 → 侧边栏"管理"组可见 系统概览/共享模型池/平台配置/审计日志。
2. 系统概览页显示 health=UP 与各计数。
3. 共享模型池新增一条 → 列表出现 → 以某租户用户发起对话仍可用（验证共享模型对租户可见）。
4. 平台配置关闭"开放注册" → 退出后注册接口返回"平台已关闭自助注册"。
5. 以租户管理员登录 → 可见 审计日志/租户配置，不可见平台专属项。
6. 任一增删改后到审计日志页可见记录，且 detail 中 apiKey/password 显示为 `***`。

- [ ] **Step 4: 检查点** — 以上手测通过即完成本计划。

---

## 自检（写完计划后对照 spec）

**1. Spec 覆盖：**
- ① 平台配置 → Task 2-5（含生效点 Task 6）✅
- ② 租户配置 → Task 2-5 ✅
- ③ 共享模型池 → Task 7-11 ✅（含隐患修复：init 改 PLATFORM + 合并路由）
- ④ 审计日志 → Task 12-16 ✅；健康概览 → Task 17-18 ✅
- 菜单与权限 → Task 19 + 各控制器 `SecurityUtil` 守卫 ✅
- 测试策略（ConfigService / 合并路由 / 审计脱敏）→ Task 3、8、14 ✅

**2. 占位符扫描：** 无 TODO/TBD；每个代码步骤含完整代码。前端个别"按实际组件名/图标名对齐"为**对现有代码的适配提示**，非占位符。

**3. 类型/命名一致性：**
- `OwnerScope` 贯穿 SysConfig 与 AiModelConfig ✅
- `platformService`/`auditService` 方法名与控制器路径一致（`/platform/*`、`/audit-logs`）✅
- `findSharedModelsByScenario` 在 Task 7 定义、Task 8 使用，命名一致 ✅
- `PageDTO.from` 复用既有工具 ✅
- `saveAsync` 在 AuditLogService 定义、AuditLogAspect 与 AuthService 使用 ✅

**已知需执行时对齐的点（非缺陷）：**
- 各既有控制器的具体方法名（Task 16）、`TenantRepository`/`ApplicationRepository` 实际类名（Task 17）、shadcn 基础组件文件是否齐全（Task 5）——执行者按 `ls` 结果对齐。
