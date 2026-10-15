package cn.railway.demand;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
@Component
public class ReceiverSecurity implements HandlerInterceptor,WebMvcConfigurer {
    private final FoundationClient foundation;
    public ReceiverSecurity(FoundationClient foundation){this.foundation=foundation;}
    public void addInterceptors(InterceptorRegistry r){r.addInterceptor(this);}
    public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        if(req.getRequestURI().equals("/api/v1/demand/health"))return true;
        if(!req.getRequestURI().startsWith("/api/v1/receiving-tasks"))throw new ReceiverFailure(403,"无权访问该接口");
        req.setAttribute("identity",foundation.identity(req));return true;
    }
    public static JsonNode identity(HttpServletRequest req){return (JsonNode)req.getAttribute("identity");}
}
