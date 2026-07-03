package com.qingyu.care.service;

import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * getActiveTemplate 的模板解析：当前租户/应用优先，缺失时回退平台级默认模板。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceTest {

    @Mock
    private PromptTemplateRepository repository;

    @InjectMocks
    private PromptTemplateService service;

    @Test
    void 当前范围有激活模板时直接返回不回退() {
        PromptTemplate own = PromptTemplate.builder().name("own").content("c").isActive(true).build();
        when(repository.findByIsActiveTrue()).thenReturn(Optional.of(own));

        assertThat(service.getActiveTemplate()).isSameAs(own);
        verify(repository, never()).findPlatformDefaultActive();
    }

    @Test
    void 当前范围无激活模板时回退平台级默认模板() {
        PromptTemplate platform = PromptTemplate.builder().name("default").content("c").isActive(true).build();
        when(repository.findByIsActiveTrue()).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultActive()).thenReturn(Optional.of(platform));

        assertThat(service.getActiveTemplate()).isSameAs(platform);
    }

    @Test
    void 当前范围与平台级都没有时抛资源不存在() {
        when(repository.findByIsActiveTrue()).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultActive()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActiveTemplate())
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
