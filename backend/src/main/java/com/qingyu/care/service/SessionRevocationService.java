package com.qingyu.care.service;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.entity.User;
import com.qingyu.care.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号或租户状态变化时让已签发的登录态立即失效（2026-09-25 安全扫描 M1）。
 *
 * <p>此前停用账号、删号、降级、冻结租户都只改库：登录时才校验这些状态，已登录的 token（默认 30 天有效）照用不误。
 * 角色更是直接存在会话里（{@code SecurityUtil.getRole} 读的是 session），降级后要等对方自己重新登录才生效——
 * 被降级的管理员在这之前仍是管理员。
 *
 * <p>吊销失败只记日志不抛：状态已经落库，不能因为清会话出错就让管理员以为「停用没成功」。
 *
 * @author ruan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRevocationService {

    private final UserRepository userRepository;

    /** 踢掉该用户的全部登录态 */
    public void revokeUser(Long userId, String reason) {
        if (userId == null) {
            return;
        }
        try {
            StpUtil.logout(userId);
            log.info("已吊销用户 {} 的全部登录态：{}", userId, reason);
        } catch (Exception e) {
            log.warn("吊销用户 {} 的登录态失败（{}）：{}", userId, reason, e.getMessage());
        }
    }

    /**
     * 踢掉该用户除当前请求所用 token 之外的登录态。用于「自己改自己的密码」：别的设备上的会话要失效，
     * 但不能把正在改密码的这个人自己也踢回登录页。
     */
    public void revokeOtherSessions(Long userId, String reason) {
        if (userId == null) {
            return;
        }
        try {
            String current = StpUtil.getTokenValue();
            List<String> tokens = StpUtil.getTokenValueListByLoginId(userId);
            for (String t : tokens) {
                if (!t.equals(current)) {
                    StpUtil.logoutByTokenValue(t);
                }
            }
            log.info("已吊销用户 {} 的其他登录态：{}", userId, reason);
        } catch (Exception e) {
            log.warn("吊销用户 {} 的其他登录态失败（{}）：{}", userId, reason, e.getMessage());
        }
    }

    /** 踢掉该租户下全部用户的登录态（冻结、到期、删除租户） */
    public void revokeTenant(Long tenantId, String reason) {
        if (tenantId == null) {
            return;
        }
        for (User u : userRepository.findByTenantId(tenantId)) {
            revokeUser(u.getId(), reason);
        }
    }
}
