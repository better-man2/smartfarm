package com.example.smartfarm.common;

/**
 * 业务异常：由 GlobalExceptionHandler 统一转换为 ApiResult。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
