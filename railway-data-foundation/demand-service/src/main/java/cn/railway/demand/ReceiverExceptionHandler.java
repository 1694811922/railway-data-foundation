package cn.railway.demand;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.*;
@RestControllerAdvice
public class ReceiverExceptionHandler {
    static ResponseEntity<?> error(int status,String message){var body=new LinkedHashMap<String,Object>();body.put("code",status==409?10009:status==401?10101:status==403?10102:status);body.put("message",message);body.put("data",null);return ResponseEntity.status(status).header("Cache-Control","no-store").body(body);}
    @ExceptionHandler(ReceiverFailure.class) ResponseEntity<?> failure(ReceiverFailure e){return error(e.status,e.getMessage());}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,jakarta.validation.ConstraintViolationException.class,org.springframework.web.method.annotation.HandlerMethodValidationException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<?> invalid(Exception e){return error(400,"接收参数不合法，请检查输入");}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unavailable(Exception e,jakarta.servlet.http.HttpServletResponse response){if(response.isCommitted())return null;response.reset();return error(503,"接收服务暂不可用，请刷新核对状态");}
}
