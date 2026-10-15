package cn.railway.foundation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=HealthController.class,excludeFilters=@org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,classes={cn.railway.foundation.security.WorkspaceGate.class,cn.railway.foundation.security.SecurityConfiguration.class}))
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters=false)
@Import({GlobalExceptionHandler.class, ApiContractTest.FailureController.class})
class ApiContractTest {
    @Autowired MockMvc mvc;

    @Test void healthContract() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("foundation-service"));
    }
    @Test void validationRejectsBlank() throws Exception {
        mvc.perform(post("/api/v1/test/validate").contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void validationAcceptsValidBody() throws Exception {
        mvc.perform(post("/api/v1/test/validate").contentType("application/json").content("{\"name\":\"demo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("demo"));
    }
    @Test void malformedJsonUsesEnvelope() throws Exception {
        mvc.perform(post("/api/v1/test/validate").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
    }
    @Test void businessFailureUsesEnvelope() throws Exception {
        mvc.perform(get("/api/v1/test/business")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10002)).andExpect(jsonPath("$.message").value("资源尚未发布"));
    }
    @Test void internalFailureDoesNotLeakDetails() throws Exception {
        mvc.perform(get("/api/v1/test/internal")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(10099)).andExpect(jsonPath("$.message").value("服务内部错误"))
                .andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void missingPathUsesEnvelope() throws Exception {
        mvc.perform(get("/api/v1/not-present")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10004));
    }
    @Test void wrongMethodUsesEnvelope() throws Exception {
        mvc.perform(post("/api/v1/health")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(10005));
    }
    @Test void wrongMediaTypeUsesEnvelope() throws Exception {
        mvc.perform(post("/api/v1/test/validate").contentType("text/plain").content("demo"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(10006));
    }

    // Test-only routes: no diagnostic/failure endpoints are shipped in the application.
    @RestController
    @RequestMapping("/api/v1/test")
    static class FailureController {
        record Input(@NotBlank String name) {}
        @PostMapping("/validate") ApiResponse<Input> validate(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }
        @GetMapping("/business") void business() {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "资源尚未发布");
        }
        @GetMapping("/internal") void internal() {
            throw new IllegalStateException("password=must-never-be-returned");
        }
    }
}
