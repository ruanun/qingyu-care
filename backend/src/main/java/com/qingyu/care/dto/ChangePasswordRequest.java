package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

/**
 * 本人修改密码（{@code POST /v1/users/me/password}）。必须带当前密码：持有一个被盗的登录态不等于知道密码，
 * 否则 token 一泄露就能改密接管账号、顺手把真正的主人踢下线（2026-09-30 安全审查 M3）。
 *
 * <p>两个字段都排除在 {@code toString} 之外：审计切面会把参数 {@code toString} 后落库，脱敏正则只认
 * {@code password=} 形式，不能指望它兜住所有写法。
 *
 * @author ruan
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "请输入当前密码")
    @ToString.Exclude
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @ToString.Exclude
    private String newPassword;
}
