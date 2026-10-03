package cn.railway.foundation.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_ARGUMENT(10001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    BUSINESS_ERROR(10002, HttpStatus.BAD_REQUEST, "业务处理失败"),
    NOT_FOUND(10004, HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(10005, HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(10006, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    INTERNAL_ERROR(10099, HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");

    private final int code;
    private final HttpStatus status;
    private final String message;

    ErrorCode(int code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }
    public int code() { return code; }
    public HttpStatus status() { return status; }
    public String message() { return message; }
}
