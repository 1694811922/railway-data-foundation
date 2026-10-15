package cn.railway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Authenticate and authorize every new request. No allow cache and no compatibility bypass. */
@Component
public class AccessRuntimeGuard {
    public static final Set<String> FIELDS=Set.of("id","container_no","event_type","event_time","port_code","yard_code","train_no","operation_status","equipment_no","source_system","created_at","updated_at");
    public static final Set<String> TIME_FIELDS=Set.of("event_time","created_at","updated_at");
    public record Scope(List<String> fields,String timeField,Instant from,Instant until) {}
    private final CapabilityRuntimeGuard runtime;
    private final String controlToken;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AccessRuntimeGuard(CapabilityRuntimeGuard runtime,Environment environment,ObjectMapper json){this.runtime=runtime;this.json=json;controlToken=environment.getProperty("PROVIDER_CONTROL_TOKEN","");}
    public void requireOperator(String token){
        if(!controlToken.matches("[A-Za-z0-9_-]{32,128}"))throw unavailable();
        if(token==null||token.length()>128||!MessageDigest.isEqual(controlToken.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw denied("仅允许运维凭据触发Kafka生产",HttpStatus.FORBIDDEN);
    }
    public Scope require(String capability,String relationship,String authorization,List<String> requested,List<String> filters,Instant from,Instant to){
        return require(capability,relationship,authorization,requested,filters,from,to,null);
    }
    public Scope require(String capability,String relationship,String authorization,List<String> requested,List<String> filters,Instant from,Instant to,RuntimeCapture capture){
        if(capability==null||!capability.matches("CAP_[a-f0-9]{32}")||relationship==null||!relationship.matches("REL_[a-f0-9]{32}"))
            throw new CapabilityRuntimeGuard.Rejected(HttpStatus.BAD_REQUEST,10004,"请提供有效的接口能力编号和共享关系编号");
        if(authorization==null||!authorization.matches("Bearer rdf_[A-Za-z0-9_-]{43}")){if(capture!=null)capture.reason("IDENTITY_INVALID");throw denied("请提供有效的需求方调用凭据",HttpStatus.UNAUTHORIZED);}
        if(!controlToken.matches("[A-Za-z0-9_-]{32,128}"))throw unavailable();
        if(requested!=null&&(requested.isEmpty()||new HashSet<>(requested).size()!=requested.size()||!FIELDS.containsAll(requested))){if(capture!=null)capture.reason("FIELDS_FORBIDDEN");throw denied("请求字段不受当前提供服务支持",HttpStatus.FORBIDDEN);}
        if(from!=null&&from.getNano()%1000000!=0||to!=null&&to.getNano()%1000000!=0||from!=null&&to!=null&&!from.isBefore(to))throw new IllegalArgumentException("时间格式或范围不合法");
        var body=new LinkedHashMap<String,Object>();body.put("relationship_id",relationship);body.put("capability_id",capability);body.put("requested_fields",requested);body.put("filter_fields",filters);body.put("from",from==null?null:from.toString());body.put("to",to==null?null:to.toString());
        try{
            var request=HttpRequest.newBuilder(runtime.foundationBase().resolve("/api/v1/access/check")).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                    .header("X-Provider-Token",controlToken).header("Authorization",authorization).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw unavailable();
            var envelope=json.readTree(response.body());
            if(envelope==null||!envelope.path("code").isInt()||envelope.path("code").intValue()!=0)throw unavailable();
            var data=envelope.path("data");
            if(!data.path("allowed").isBoolean()||!capability.equals(data.path("capability_id").asText())||!relationship.equals(data.path("relationship_id").asText()))throw unavailable();
            if(!data.path("allowed").booleanValue()){
                String reason=data.path("reason_code").asText();if(!RuntimeCapture.DENIALS.contains(reason))throw unavailable();
                if(capture!=null){capture.reason(reason);if(!reason.equals("IDENTITY_INVALID"))capture.governance(governance(data,relationship,capability,false));}
                throw denied(data.path("reason").asText("共享访问被拒绝"),"IDENTITY_INVALID".equals(reason)?HttpStatus.UNAUTHORIZED:HttpStatus.FORBIDDEN);
            }
            if(!"ALLOWED".equals(data.path("reason_code").asText())||!data.path("subject_id").asText().matches("SUB_[a-f0-9]{32}")
                    ||!data.path("resource_id").asText().matches("RES_[a-f0-9]{32}")||!data.path("policy_id").asText().matches("POL_[a-f0-9]{32}")
                    ||!data.path("relationship_version").isInt()||data.path("relationship_version").intValue()<1||!data.path("policy_version").isInt()||data.path("policy_version").intValue()<1)throw unavailable();
            if(capture!=null)capture.governance(governance(data,relationship,capability,true));
            if(!runtime.sourceHost().equalsIgnoreCase(data.path("source_host").asText())||!data.path("source_port").isInt()||runtime.sourcePort()!=data.path("source_port").intValue()
                    ||!runtime.databaseName().equals(data.path("database_name").asText())||!"container_event".equals(data.path("physical_table").asText())){if(capture!=null)capture.reason("CAPABILITY_UNAVAILABLE");throw denied("授权来源与当前提供服务不一致",HttpStatus.FORBIDDEN);}
            var names=new ArrayList<String>();var allowed=data.path("allowed_fields");if(!allowed.isArray()||allowed.isEmpty())throw unavailable();
            for(var field:allowed){if(!field.isTextual()||!FIELDS.contains(field.asText())||names.contains(field.asText()))throw unavailable();names.add(field.asText());}
            if(requested!=null&&(!names.equals(requested)))throw unavailable();
            var approvedFilters=data.path("filter_fields");if(!approvedFilters.isArray()||!json.valueToTree(filters).equals(approvedFilters))throw unavailable();
            String timeField=data.path("time_field").asText();if(!TIME_FIELDS.contains(timeField))throw unavailable();
            Instant lower=Instant.parse(data.path("data_from").asText()),upper=Instant.parse(data.path("data_until").asText()),checked=Instant.parse(data.path("checked_at").asText()),expires=Instant.parse(data.path("valid_until").asText()),now=Instant.now();
            if(!lower.isBefore(upper)||from!=null&&!lower.equals(from)||to!=null&&!upper.equals(to)||!expires.isAfter(now)||!expires.isAfter(checked)||checked.isAfter(now.plusSeconds(5))||checked.isBefore(now.minusSeconds(10)))throw unavailable();
            if(lower.getNano()%1000000!=0||upper.getNano()%1000000!=0)throw unavailable();
            return new Scope(List.copyOf(names),timeField,lower,upper);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw unavailable();}
        catch(IOException|DateTimeException|IllegalArgumentException ex){throw unavailable();}
    }
    private static RuntimeObservation.Governance governance(com.fasterxml.jackson.databind.JsonNode data,String relationship,String capability,boolean required){
        String subject=optionalId(data,"subject_id","SUB"),resource=optionalId(data,"resource_id","RES"),policy=optionalId(data,"policy_id","POL");
        Integer relationVersion=optionalVersion(data,"relationship_version"),policyVersion=optionalVersion(data,"policy_version");
        if(required&&(subject==null||resource==null||policy==null||relationVersion==null||policyVersion==null)
                ||(policy==null)!=(policyVersion==null)||resource!=null&&(subject==null||policy==null||relationVersion==null))throw unavailable();
        return new RuntimeObservation.Governance(relationship,capability,subject,resource,policy,relationVersion,policyVersion);
    }
    private static String optionalId(com.fasterxml.jackson.databind.JsonNode data,String key,String prefix){var value=data.path(key);if(value.isMissingNode()||value.isNull())return null;if(!value.isTextual()||!value.textValue().matches(prefix+"_[a-f0-9]{32}"))throw unavailable();return value.textValue();}
    private static Integer optionalVersion(com.fasterxml.jackson.databind.JsonNode data,String key){var value=data.path(key);if(value.isMissingNode()||value.isNull())return null;if(!value.isInt()||value.intValue()<1)throw unavailable();return value.intValue();}
    private static CapabilityRuntimeGuard.Rejected denied(String reason,HttpStatus status){return new CapabilityRuntimeGuard.Rejected(status,10102,reason);}
    private static CapabilityRuntimeGuard.Rejected unavailable(){return new CapabilityRuntimeGuard.Rejected(HttpStatus.SERVICE_UNAVAILABLE,10103,"共享授权校验暂不可用，请稍后重试");}
}
