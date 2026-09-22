package com.example.smartfarm.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色限制。可标注在 Controller 类或方法上。
 * 不标注表示"只要登录即可访问"。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleRequired {

    /** 允许访问的角色，默认仅管理员 */
    String[] value() default {Constants.ROLE_ADMIN};
}
