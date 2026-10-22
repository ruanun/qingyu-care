package com.qingyu.care.annotation;

import java.lang.annotation.*;

/**
 * 标注在需要记录操作审计的 Controller 方法上。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {
    /** 动作，如 TENANT_CREATE、MODEL_UPDATE */
    String action();
    /** 目标类型，如 Tenant、AiModelConfig */
    String targetType() default "";
}
