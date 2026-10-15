package cn.railway.demand;
import jakarta.servlet.http.*;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.file.Files;
@RestController @Validated
@RequestMapping("/api/v1/receiving-tasks/{id}/messages")
public class ReceivingDownloadController {
    private final ReceivingDownloadService downloads;private final FoundationClient foundation;
    public ReceivingDownloadController(ReceivingDownloadService downloads,FoundationClient foundation){this.downloads=downloads;this.foundation=foundation;}
    @GetMapping("/{message}/download") public void single(@PathVariable String id,@PathVariable @Min(1) long message,@RequestParam(defaultValue="content") @Pattern(regexp="content|record") String format,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var identity=ReceiverSecurity.identity(request);try(var file=downloads.single(id,identity.path("subject_id").asText(),message,format)){send(file,identity,request,response);}
    }
    @GetMapping("/export") public void export(@PathVariable String id,@RequestParam(required=false) @Size(max=249) String topic,@RequestParam(required=false) @Min(0) Integer partition,@RequestParam(required=false) @Size(max=40) String from,@RequestParam(required=false) @Size(max=40) String until,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var identity=ReceiverSecurity.identity(request);try(var file=downloads.export(id,identity.path("subject_id").asText(),topic,partition,from,until)){send(file,identity,request,response);}
    }
    private void send(ReceivingDownloadService.Download file,com.fasterxml.jackson.databind.JsonNode before,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var after=foundation.identity(request);if(!before.path("user_id").equals(after.path("user_id"))||!before.path("subject_id").equals(after.path("subject_id")))throw new ReceiverFailure(403,"登录身份已变化，下载已取消");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Disposition","attachment; filename=\""+file.name+"\"");response.setHeader("X-Download-Bytes",Long.toString(file.bytes));response.setHeader("X-Export-Count",Long.toString(file.count));response.setContentType(file.type);response.setContentLengthLong(file.bytes);
        try(var in=Files.newInputStream(file.file)){in.transferTo(response.getOutputStream());response.flushBuffer();}
    }
}
