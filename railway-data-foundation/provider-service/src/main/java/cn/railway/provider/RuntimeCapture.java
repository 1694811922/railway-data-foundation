package cn.railway.provider;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/** Request-local metadata only. Mutable while executing, converted to an immutable summary after completion. */
public final class RuntimeCapture {
    public static final String ATTRIBUTE=RuntimeCapture.class.getName();
    public static final Set<String> DENIALS=Set.of("IDENTITY_INVALID","RELATIONSHIP_UNAVAILABLE","SUBJECT_UNAVAILABLE",
            "RELATIONSHIP_INACTIVE","RELATIONSHIP_EXPIRED","POLICY_UNAVAILABLE","FIELDS_FORBIDDEN","TIME_FORBIDDEN",
            "CAPABILITY_UNAVAILABLE","INVALID_REQUEST","OPERATOR_FORBIDDEN");
    final String traceId=UUID.randomUUID().toString();
    final String eventId=UUID.randomUUID().toString();
    final String point;
    final Instant started=Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final long nanos=System.nanoTime();
    RuntimeObservation.Governance governance;
    String reason;
    long rows;
    boolean success;
    String topic;
    Integer partition;
    Long offset;
    long kafkaBytes;
    public RuntimeCapture(String point,String relationship,String capability) {
        this.point=point;
        governance=new RuntimeObservation.Governance(point.equals("REST_PROVIDER")?id(relationship,"REL"):null,point.equals("REST_PROVIDER")?id(capability,"CAP"):null,null,null,null,null,null);
    }
    public static RuntimeCapture from(HttpServletRequest request){return (RuntimeCapture)request.getAttribute(ATTRIBUTE);}
    public static void reason(HttpServletRequest request,String value){var c=from(request);if(c!=null&&(c.reason==null||c.reason.equals("COMPLETED")))c.reason=value;}
    public void governance(RuntimeObservation.Governance value){governance=value;}
    public void reason(String value){reason=value;}
    public void restSuccess(long count){rows=count;success=true;reason="COMPLETED";}
    public void kafkaSuccess(KafkaEventPublisher.Receipt receipt,long bytes){rows=1;success=true;reason="COMPLETED";topic=receipt.topic();partition=receipt.partition();offset=receipt.offset();kafkaBytes=bytes;}
    RuntimeObservation finish(int status,long bytes,boolean failed) {
        var completed=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if(completed.isBefore(started))completed=started; // wall-clock adjustment must not invert an observed interval
        boolean ok=!failed&&success&&"COMPLETED".equals(reason)&&status>=200&&status<300;
        String outcome=ok?"SUCCESS":status>=500||failed||success?"ERROR":"DENIED";
        String code=ok?"COMPLETED":reason;
        if(outcome.equals("DENIED")&&!DENIALS.contains(code==null?"":code))code="INVALID_REQUEST";
        if(outcome.equals("ERROR")&&!Set.of("SOURCE_UNAVAILABLE","CONTROL_UNAVAILABLE","SERIALIZATION_ERROR","INTERNAL_ERROR","KAFKA_UNAVAILABLE").contains(code==null?"":code))code=failed?"SERIALIZATION_ERROR":"INTERNAL_ERROR";
        return new RuntimeObservation(eventId,traceId,governance.relationshipId(),governance.capabilityId(),governance.subjectId(),governance.resourceId(),governance.policyId(),
                governance.relationshipVersion(),governance.policyVersion(),started,completed,outcome,code,status,ok?rows:0,
                ok?(point.equals("REST_PROVIDER")?bytes:kafkaBytes):0,Math.max(0,(System.nanoTime()-nanos)/1000000),ok?topic:null,ok?partition:null,ok?offset:null);
    }
    private static String id(String value,String prefix){return value!=null&&value.matches(prefix+"_[a-f0-9]{32}")?value:null;}
}
