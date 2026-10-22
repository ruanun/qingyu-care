package com.qingyu.care.dto;

import com.qingyu.care.enums.UserRole;
import lombok.Builder;
import lombok.Data;

/**
 * 登录响应
 */
@Data
@Builder
public class LoginResponse {
    
    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private UserRole role;
    private Long tenantId;
    private String tenantName;
}
