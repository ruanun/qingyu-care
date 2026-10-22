package com.qingyu.care.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在「用 POST 实现、但语义是只读查询」的端点上（如批量查状态，id 列表放请求体），
 * 让 {@link AdminOnlyWrites} 不拦它。⚠️ 只给真正无副作用的端点用：会触发外部请求、写库或
 * 消耗额度的端点（如解析远程 OpenAPI schema、测试模型连接）哪怕名字像查询，也不属于此类。
 *
 * @author ruan
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReadOnlyEndpoint {
}
