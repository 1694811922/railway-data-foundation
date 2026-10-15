package cn.railway.demand;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class ReceivingDownloadTest {
    @Test void formatDetectionIsStrictAndBinarySafe(){assertThat(ReceivingDownloadService.kind("{\"中文\":1}".getBytes(StandardCharsets.UTF_8))).isEqualTo("json");assertThat(ReceivingDownloadService.kind("{} trailing".getBytes(StandardCharsets.UTF_8))).isEqualTo("txt");assertThat(ReceivingDownloadService.kind("<script>x</script>".getBytes(StandardCharsets.UTF_8))).isEqualTo("txt");assertThat(ReceivingDownloadService.kind(new byte[]{(byte)255})).isEqualTo("bin");assertThat(ReceivingDownloadService.kind(new byte[0])).isEqualTo("txt");}
    @Test void sizeLimitStopsBeforeWritingExtraBytes()throws Exception{var raw=new ByteArrayOutputStream();var limited=new ReceivingDownloadService.LimitedOutput(raw,3);limited.write(new byte[]{1,2,3});assertThatThrownBy(()->limited.write(4)).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(413));assertThat(raw.toByteArray()).containsExactly((byte)1,(byte)2,(byte)3);}
    @Test void streamFailureCannotLeaveStaleFileHeadersOrAppendJson(){var response=new org.springframework.mock.web.MockHttpServletResponse();response.setHeader("Content-Disposition","attachment");response.setContentLengthLong(99);var handler=new ReceiverExceptionHandler();assertThat(handler.unavailable(new java.io.IOException(),response).getStatusCode().value()).isEqualTo(503);assertThat(response.getHeader("Content-Disposition")).isNull();response.setCommitted(true);assertThat(handler.unavailable(new java.io.IOException(),response)).isNull();}
}
