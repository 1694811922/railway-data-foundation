package cn.railway.foundation.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
        return ResponseEntity.status(ex.errorCode().status())
                .body(ApiResponse.failure(ex.errorCode(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception ex) {
        // Do not echo rejected values: they may contain passwords or tokens.
        return error(ErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(Exception ex) { return error(ErrorCode.NOT_FOUND); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> method(Exception ex) { return error(ErrorCode.METHOD_NOT_ALLOWED); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> media(Exception ex) { return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        LOG.error("Unhandled request failure, exception type: {}", ex.getClass().getName());
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> error(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiResponse.failure(code, code.message()));
    }
}
