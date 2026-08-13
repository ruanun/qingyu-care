package com.qingyu.care.dto;

import com.qingyu.care.enums.UserRole;
import lombok.Data;

/**
 * 用户局部更新请求。
 *
 * <p><b>为什么不直接用 {@code User} 实体收请求体</b>：实体字段带 Lombok
 * {@code @Builder.Default}（{@code enabled}），而 Lombok 会把默认值也应用进无参构造，Jackson
 * 反序列化走的正是那个无参构造——于是请求体里<b>没给</b>的字段拿到的是类默认值而非
 * {@code null}，{@code if (x != null)} 恒真，把库里的值覆盖掉。
 *
 * <p>⚠️ <b>{@code password} 必须用 {@code != null && !isEmpty()} 判断，不能只判
 * null</b>。{@code UserListPage} 的编辑弹窗把 {@code password} 初始化为空串（注释写着「编辑时
 * 不显示/修改密码除非输入」）却在提交时一起发出，原先后端用 {@code containsKey("password")}
 * 判断，空串同样成立 → {@code BCrypt.hashpw("")}。后果是管理员改一下昵称，该用户密码被静默重置成
 * 空串哈希：旧密码登不上，空密码又被 {@code LoginRequest} 的 {@code @NotBlank} 拒掉，用户被彻底
 * 锁死且毫无提示。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器、不加 {@code @Builder.Default}、不加
 * {@code @Builder}</b>——{@code null} 必须真正表示「请求体没给这个字段」。加了 builder 就会
 * 诱使下一个人写 {@code @Builder.Default}，把坑重新种回来。反例见
 * {@code KnowledgeBaseRequest.enabled}（它现在没出事只因为前端建/改知识库都发整份对象）。
 *
 * @author ruan
 */
@Data
public class UserUpdateRequest {
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private Boolean enabled;
    private UserRole role;
    /** 留空表示不修改密码——前端编辑弹窗把它初始化为空串并照样提交，见类注释 */
    private String password;
}
