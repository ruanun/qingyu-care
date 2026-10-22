package com.qingyu.care.repository;

import com.qingyu.care.entity.AiSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiSkillRepository extends JpaRepository<AiSkill, Long> {

    /** 取当前应用启用的 skill（全量注入用） */
    List<AiSkill> findByEnabledTrue();

    /** 显式按 appId 过滤（管理面 list 用，防平台管理员裸 findAll 跨应用读取） */
    List<AiSkill> findByAppId(Long appId);
}
