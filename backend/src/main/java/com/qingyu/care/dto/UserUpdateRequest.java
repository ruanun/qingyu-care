package com.qingyu.care.dto;

import com.qingyu.care.enums.UserRole;
import lombok.Data;

/**
 * 用户局部更新请求。
 *
 * <p><b>为什么不直接用 {@code User} 实体收请求体</b>：实体字段写了初始化器（如
 * {@code private Boolean enabled = true;}），Jackson 反序列化用的无参构造会原样执行这些
 * 初始化器——于是请求体里<b>没给</b>的字段拿到的是类默认值而非 {@code null}，
 * {@code if (x != null)} 恒真，把库里的值覆盖掉。实体上 {@code enabled} 还额外标了
 * {@code @Builder.Default}，但<b>真正起效的是 {@code = true} 这个初始化器</b>：
 * {@code @Builder.Default} 只影响 {@code User.builder().build()} 这条路径是否也用上该默认值，
 * 与 Jackson 走的无参构造无关，删掉它不会修好这个缺陷（证据见本类末段引用的
 * {@code KnowledgeBaseRequest} 反例）。
 *
 * <p>⚠️ <b>{@code password} 必须用 {@code != null && !isBlank()} 判断，不能只判
 * null</b>。{@code UserListPage} 的编辑弹窗把 {@code password} 初始化为空串（注释写着「编辑时
 * 不显示/修改密码除非输入」）却在提交时一起发出，原先后端用 {@code containsKey("password")}
 * 判断，空串同样成立 → {@code BCrypt.hashpw("")}。后果是管理员改一下昵称，该用户密码被静默重置成
 * 空串哈希：旧密码登不上，空密码又被 {@code LoginRequest} 的 {@code @NotBlank} 拒掉，用户被彻底
 * 锁死且毫无提示。仅判 {@code isEmpty} 挡不住全空格密码（{@code "   "}），同样一路穿到
 * {@code BCrypt.hashpw("   ")}，故须用 {@code isBlank}。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器</b>——这才是真正的铁律，{@code null} 必须真正表示
 * 「请求体没给这个字段」。<b>删掉 {@code @Builder.Default} 或不加 {@code @Builder} 都不能替代
 * 这条</b>：Jackson 走的无参构造只看字段是否有初始化器，与 {@code @Builder.Default} 无关——
 * {@code @Builder.Default} 只决定 {@code builder().build()} 这条路径是否也应用该默认值，不加
 * 它时 Lombok 会警告「初始化表达式被忽略」，但 {@code new User()} 依旧会应用初始化器。反证见
 * {@code KnowledgeBaseRequest.enabled}：它有 {@code @Builder} + {@code private Boolean enabled
 * = true;} 却<b>没有</b> {@code @Builder.Default}，{@code new KnowledgeBaseRequest().getEnabled()}
 * 依然是 {@code true}（它现在没出事只因为前端建/改知识库都发整份对象，不代表这条路径安全）。
 *
 * <p>⚠️ <b>与本批其余 DTO 不同，{@code User} 不适用「实体初始化器在 create 路径承重」这条
 * 反向提醒</b>：{@code UserController.createUser} 收的是 {@code Map<String,Object>} 而非
 * {@code User} 实体本身（手工取字段、显式 {@code .enabled(true)} 落 builder），从未经 Jackson
 * 直接反序列化到 {@code User}，故 {@code enabled} 的 {@code @Builder.Default} 初始化器在
 * create 路径上不承担任何职责——这条铁律在本类上没有例外要留意。
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
