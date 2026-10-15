package cn.railway.foundation.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    UNAUTHENTICATED(10101, HttpStatus.UNAUTHORIZED, "请登录后操作"),
    INVALID_ARGUMENT(10001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    BUSINESS_ERROR(10002, HttpStatus.BAD_REQUEST, "业务处理失败"),
    NOT_FOUND(10004, HttpStatus.NOT_FOUND, "请求的资源不存在"),
    VERSION_CONFLICT(10009, HttpStatus.CONFLICT, "资源已被修改，请刷新后重新编辑"),
    METHOD_NOT_ALLOWED(10005, HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(10006, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    INTERNAL_ERROR(10099, HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    ACCESS_FORBIDDEN(10102, HttpStatus.FORBIDDEN, "共享访问被拒绝"),
    ACCESS_UNAVAILABLE(10103, HttpStatus.SERVICE_UNAVAILABLE, "共享访问校验暂不可用");

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
