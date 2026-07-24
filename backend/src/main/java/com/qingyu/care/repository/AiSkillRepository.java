package com.qingyu.care.repository;

import com.qingyu.care.entity.AiSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiSkillRepository extends JpaRepository<AiSkill, Long> {

    /** 取当前应用启用的 skill（全量注入用） */
    List<AiSkill> findByEnabledTrue();
}
