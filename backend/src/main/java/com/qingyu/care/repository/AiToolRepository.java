package com.qingyu.care.repository;

import com.qingyu.care.entity.AiTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AiToolRepository extends JpaRepository<AiTool, Long> {

    /** 按 id 集合取启用工具（skill 激活时用） */
    List<AiTool> findByIdInAndEnabledTrue(Collection<Long> ids);
}
