package com.qingyu.care.enums;

/**
 * 能力包的上架状态。
 *
 * <p>⚠️ 租户侧列表<b>只返回 {@link #PUBLISHED}</b>：{@link #DRAFT} 是「导出后还没补完文案与
 * 变量说明」的中间态，让租户看见等于让人装一个变量连标签都没有的包。
 *
 * @author ruan
 */
public enum PackageStatus {
    /** 草稿：刚导出，平台管理员补文案中，租户不可见 */
    DRAFT,
    /** 已发布：租户可见可装 */
    PUBLISHED,
    /** 已下架：租户不可见；已装出来的副本<b>不受影响</b>（安装是一次性的，装完与包断开） */
    ARCHIVED
}
