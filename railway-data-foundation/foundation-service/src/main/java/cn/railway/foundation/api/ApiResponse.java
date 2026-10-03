package cn.railway.foundation.api;

public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> failure(ErrorCode code, String message) {
        return new ApiResponse<>(code.code(), message, null);
    }
}
