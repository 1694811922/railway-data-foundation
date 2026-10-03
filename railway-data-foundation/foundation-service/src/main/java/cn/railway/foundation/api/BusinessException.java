package cn.railway.foundation.api;

/** Messages must be safe for clients: never include credentials or raw SQL/JDBC errors. */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    public ErrorCode errorCode() { return errorCode; }
}
