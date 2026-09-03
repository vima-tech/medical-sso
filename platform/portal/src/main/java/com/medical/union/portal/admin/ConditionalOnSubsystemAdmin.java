package com.medical.union.portal.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 子系统登记功能的开关条件。
 *
 * <p>配置类和控制器必须共用同一个条件：只关掉配置类会让控制器仍被组件扫描到，
 * 却找不到它依赖的 Bean，导致整个门户启动失败。写成一处定义避免两边漂移。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "portal.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnSubsystemAdmin {
}
