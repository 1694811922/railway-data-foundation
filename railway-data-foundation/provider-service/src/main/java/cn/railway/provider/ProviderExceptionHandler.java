package cn.railway.provider;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ProviderExceptionHandler {
    @ExceptionHandler(CapabilityRuntimeGuard.Rejected.class)
    public ResponseEntity<ContainerEventController.Response<Void>> runtimeRejected(CapabilityRuntimeGuard.Rejected ex) {
        return ResponseEntity.status(ex.status())
                .body(new ContainerEventController.Response<>(ex.code(),ex.getMessage(),null));
    }
    @ExceptionHandler({ConstraintViolationException.class,HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    public ResponseEntity<ContainerEventController.Response<Void>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(new ContainerEventController.Response<>(10001,"请求参数不合法",null));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ContainerEventController.Response<Void>> unavailable(DataAccessException ex) {
        // JDBC messages can contain server names and SQL. Do not return them to callers.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ContainerEventController.Response<>(10002,"Source 数据暂不可用",null));
    }
    @ExceptionHandler(KafkaEventPublisher.Unavailable.class)
    public ResponseEntity<ContainerEventController.Response<Void>> kafkaUnavailable(KafkaEventPublisher.Unavailable ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ContainerEventController.Response<>(10003,"Kafka 暂不可用或未配置",null));
    }
}
