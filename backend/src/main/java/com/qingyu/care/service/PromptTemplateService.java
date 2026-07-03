package com.qingyu.care.service;

import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptTemplateService {

    private final PromptTemplateRepository repository;

    @Value("${qingyu.care.prompt.system-template}")
    private String defaultTemplate;

    /**
     * 初始化：如果数据库中没有模板，则创建默认模板
     */
    @PostConstruct
    @Transactional
    public void init() {
        if (repository.count() == 0) {
            log.info("Initializing default prompt template...");
            PromptTemplate template = PromptTemplate.builder()
                    .name("default")
                    .content(defaultTemplate)
                    .version(1)
                    .isActive(true)
                    .description("系统默认模板")
                    .build();
            repository.save(template);
            log.info("Default prompt template created");
        }
    }

    /**
     * 获取当前激活的模板
     */
    public PromptTemplate getActiveTemplate() {
        // 先取当前租户/应用范围内的激活模板；缺失时回退到平台级默认模板
        // （@PostConstruct 建的默认模板 tenant_id 为空，会被租户过滤挡住，故需显式回退）。
        return repository.findByIsActiveTrue()
                .or(repository::findPlatformDefaultActive)
                .orElseThrow(() -> new ResourceNotFoundException("没有激活的 Prompt 模板"));
    }

    /**
     * 获取激活模板的内容
     */
    public String getActiveTemplateContent() {
        return getActiveTemplate().getContent();
    }

    /**
     * 获取所有版本
     */
    public List<PromptTemplate> getAllVersions() {
        return repository.findAllByOrderByVersionDesc();
    }

    /**
     * 获取指定模板
     */
    public PromptTemplate getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prompt 模板", id.toString()));
    }

    /**
     * 创建新版本
     */
    @Transactional
    public PromptTemplate createVersion(String name, String content, String description) {
        Integer maxVersion = repository.findMaxVersion();
        int newVersion = maxVersion + 1;

        PromptTemplate template = PromptTemplate.builder()
                .name(name)
                .content(content)
                .version(newVersion)
                .isActive(false)
                .description(description)
                .build();

        return repository.save(template);
    }

    /**
     * 激活指定版本
     */
    @Transactional
    public PromptTemplate activateVersion(Long id) {
        PromptTemplate template = getById(id);
        
        // 先将所有模板设为非激活
        repository.deactivateAll();
        
        // 激活指定模板
        template.setIsActive(true);
        log.info("Activated prompt template version {} (id={})", template.getVersion(), id);
        
        return repository.save(template);
    }

    /**
     * 删除版本（不允许删除激活版本）
     */
    @Transactional
    public void deleteVersion(Long id) {
        PromptTemplate template = getById(id);
        if (template.getIsActive()) {
            throw new com.qingyu.care.exception.InvalidOperationException("不能删除激活中的模板");
        }
        repository.delete(template);
        log.info("Deleted prompt template version {} (id={})", template.getVersion(), id);
    }
}
