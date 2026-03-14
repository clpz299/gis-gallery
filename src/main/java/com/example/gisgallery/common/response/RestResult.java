package com.example.gisgallery.common.response;

import lombok.Data;

/**
 * 统一API响应结果封装类
 * 使用泛型 T 来支持不同类型的返回数据
 * 符合 RESTful 风格，包含状态码、消息和数据
 *
 * @author clpz299
 */
@Data
public class RestResult<T> {

    /**
     * 状态码
     */
    private long code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 数据封装
     */
    private T data;

    protected RestResult() {
    }

    protected RestResult(long code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功返回结果
     *
     * @param data 获取的数据
     */
    public static <T> RestResult<T> success(T data) {
        return new RestResult<T>(RestResultCode.SUCCESS.getCode(), RestResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功返回结果
     *
     * @param data    获取的数据
     * @param message 提示信息
     */
    public static <T> RestResult<T> success(T data, String message) {
        return new RestResult<T>(RestResultCode.SUCCESS.getCode(), message, data);
    }

    /**
     * 失败返回结果
     *
     * @param errorCode 错误码
     */
    public static <T> RestResult<T> failed(RestResultCode errorCode) {
        return new RestResult<T>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败返回结果
     *
     * @param errorCode 错误码
     * @param message   错误信息
     */
    public static <T> RestResult<T> failed(RestResultCode errorCode, String message) {
        return new RestResult<T>(errorCode.getCode(), message, null);
    }

    /**
     * 失败返回结果
     *
     * @param message 提示信息
     */
    public static <T> RestResult<T> failed(String message) {
        return new RestResult<T>(RestResultCode.FAILED.getCode(), message, null);
    }

    /**
     * 失败返回结果
     */
    public static <T> RestResult<T> failed() {
        return failed(RestResultCode.FAILED);
    }

    /**
     * 参数验证失败返回结果
     */
    public static <T> RestResult<T> validateFailed() {
        return failed(RestResultCode.VALIDATE_FAILED);
    }

    /**
     * 参数验证失败返回结果
     *
     * @param message 提示信息
     */
    public static <T> RestResult<T> validateFailed(String message) {
        return new RestResult<T>(RestResultCode.VALIDATE_FAILED.getCode(), message, null);
    }

    /**
     * 未登录返回结果
     */
    public static <T> RestResult<T> unauthorized(T data) {
        return new RestResult<T>(RestResultCode.UNAUTHORIZED.getCode(), RestResultCode.UNAUTHORIZED.getMessage(), data);
    }

    /**
     * 未授权返回结果
     */
    public static <T> RestResult<T> forbidden(T data) {
        return new RestResult<T>(RestResultCode.FORBIDDEN.getCode(), RestResultCode.FORBIDDEN.getMessage(), data);
    }
}
