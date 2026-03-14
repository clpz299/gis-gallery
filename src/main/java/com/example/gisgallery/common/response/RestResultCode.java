package com.example.gisgallery.common.response;

import lombok.Getter;

/**
 * 响应状态码枚举
 * 定义系统中所有的业务状态码，方便统一管理和维护
 *
 * @author clpz299
 */
@Getter
public enum RestResultCode {

    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(404, "参数检验失败"),
    UNAUTHORIZED(401, "暂无登录或token已经过期"),
    FORBIDDEN(403, "没有相关权限");

    private final long code;
    private final String message;

    RestResultCode(long code, String message) {
        this.code = code;
        this.message = message;
    }

}
