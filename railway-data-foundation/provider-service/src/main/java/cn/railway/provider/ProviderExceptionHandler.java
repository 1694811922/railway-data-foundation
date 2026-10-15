package cn.railway.provider;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotWritableException;

@RestControllerAdvice
public class ProviderExceptionHandler {
    @ExceptionHandler(CapabilityRuntimeGuard.Rejected.class)
    public ResponseEntity<ContainerEventController.Response<Void>> runtimeRejected(CapabilityRuntimeGuard.Rejected ex,HttpServletRequest request) {
        RuntimeCapture.reason(request,ex.status().value()>=500?"CONTROL_UNAVAILABLE":ex.status().value()==401?"IDENTITY_INVALID":"INVALID_REQUEST");
        return ResponseEntity.status(ex.status())
                .body(new ContainerEventController.Response<>(ex.code(),ex.getMessage(),null));
    }
    @ExceptionHandler({ConstraintViolationException.class,HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    public ResponseEntity<ContainerEventController.Response<Void>> invalid(Exception ex,HttpServletRequest request) {
        RuntimeCapture.reason(request,"INVALID_REQUEST");
        return ResponseEntity.badRequest().body(new ContainerEventController.Response<>(10001,"请求参数不合法",null));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ContainerEventController.Response<Void>> unavailable(DataAccessException ex,HttpServletRequest request) {
        RuntimeCapture.reason(request,"SOURCE_UNAVAILABLE");
        // JDBC messages can contain server names and SQL. Do not return them to callers.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ContainerEventController.Response<>(10002,"Source 数据暂不可用",null));
    }
    @ExceptionHandler(KafkaEventPublisher.Unavailable.class)
    public ResponseEntity<ContainerEventController.Response<Void>> kafkaUnavailable(KafkaEventPublisher.Unavailable ex,HttpServletRequest request) {
        RuntimeCapture.reason(request,"KAFKA_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ContainerEventController.Response<>(10003,"Kafka 暂不可用或未配置",null));
    }
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ContainerEventController.Response<Void>> outputFailed(HttpMessageNotWritableException ex,HttpServletRequest request){
        RuntimeCapture.reason(request,"SERIALIZATION_ERROR");return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ContainerEventController.Response<>(10099,"响应输出未完成",null));
    }
}
