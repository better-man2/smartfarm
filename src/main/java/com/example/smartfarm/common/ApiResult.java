package com.example.smartfarm.common;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一接口响应体。
 * 前端统一按 { success, message, data } 解析。
 */
@Data
public class ApiResult<T> {

    /** 业务是否成功 */
    private boolean success;
    /** 提示信息 */
    private String message;
    /** 业务数据 */
    private T data;
    /** 附加信息（如分页总数、统计口径等） */
    private Map<String, Object> extra;

    public ApiResult() {
    }

    public ApiResult(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> ok() {
        return new ApiResult<>(true, "操作成功", null);
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, "操作成功", data);
    }

    public static <T> ApiResult<T> ok(String message, T data) {
        return new ApiResult<>(true, message, data);
    }

    public static <T> ApiResult<T> fail(String message) {
        return new ApiResult<>(false, message, null);
    }

    public static <T> ApiResult<T> fail(boolean success, String message) {
        return new ApiResult<>(success, message, null);
    }

    /** 追加附加信息，返回自身便于链式调用 */
    public ApiResult<T> put(String key, Object value) {
        if (this.extra == null) {
            this.extra = new HashMap<>();
        }
        this.extra.put(key, value);
        return this;
    }
}
