package cn.railway.provider;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Deliberately has no request, response, SQL, filter values, credentials or business row fields. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RuntimeObservation(String eventId,String traceId,String relationshipId,String capabilityId,
                                 String subjectId,String resourceId,String policyId,Integer relationshipVersion,Integer policyVersion,
                                 Instant startedAt,Instant completedAt,String outcome,String reasonCode,int httpStatus,
                                 long recordCount,long byteCount,long durationMs,String topic,Integer topicPartition,Long topicOffset) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Report(String reportId,String collectionPoint,Instant windowStart,Instant windowUntil,boolean windowClosed,List<RuntimeObservation> events) {}
    public record Governance(String relationshipId,String capabilityId,String subjectId,String resourceId,String policyId,
                             Integer relationshipVersion,Integer policyVersion) {}
}
