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

    private final SysConfigRepository sysConfigRepository;

    public Map<String, String> getAll(OwnerScope scope, Long tenantId) {
        var list = (scope == OwnerScope.PLATFORM)
                ? sysConfigRepository.findByScopeAndTenantIdIsNull(scope)
                : sysConfigRepository.findByScopeAndTenantId(scope, tenantId);
        Map<String, String> map = new LinkedHashMap<>();
        list.forEach(c -> map.put(c.getConfigKey(), c.getConfigValue()));
        return map;
    }

    public String get(OwnerScope scope, Long tenantId, String key, String defaultValue) {
        Optional<SysConfig> found = (scope == OwnerScope.PLATFORM)
                ? sysConfigRepository.findByScopeAndConfigKeyAndTenantIdIsNull(scope, key)
                : sysConfigRepository.findByScopeAndTenantIdAndConfigKey(scope, tenantId, key);
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

    public long getLong(OwnerScope scope, Long tenantId, String key, long defaultValue) {
        String v = get(scope, tenantId, key, null);
        try {
            return v == null ? defaultValue : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void set(OwnerScope scope, Long tenantId, String key, String value, String description) {
        Optional<SysConfig> found = (scope == OwnerScope.PLATFORM)
                ? sysConfigRepository.findByScopeAndConfigKeyAndTenantIdIsNull(scope, key)
                : sysConfigRepository.findByScopeAndTenantIdAndConfigKey(scope, tenantId, key);
        SysConfig config = found.orElseGet(() -> SysConfig.builder()
                .scope(scope)
                .tenantId(scope == OwnerScope.PLATFORM ? null : tenantId)
                .configKey(key)
                .build());
        config.setConfigValue(value);
        if (description != null) {
            config.setDescription(description);
        }
        sysConfigRepository.save(config);
    }

    @Transactional
    public void setAll(OwnerScope scope, Long tenantId, Map<String, String> values) {
        values.forEach((k, v) -> set(scope, tenantId, k, v, null));
    }
}
