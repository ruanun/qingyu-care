package com.qingyu.care.dto;

import com.qingyu.care.enums.UserRole;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {
    
    @NotBlank(message = "用户名不能为空")
    private String username;
    
    @NotBlank(message = "密码不能为空")
    private String password;
    
    private String nickname;
    
    private String email;
    
    private String phone;
    
    /**
     * 租户编码 (可选，不填则创建新租户)
     */
    private String tenantCode;
    
    /**
     * 租户名称 (创建新租户时使用)
     */
    private String tenantName;
}
