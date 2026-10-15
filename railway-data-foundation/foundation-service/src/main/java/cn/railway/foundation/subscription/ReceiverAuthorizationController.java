package cn.railway.foundation.subscription;

import cn.railway.foundation.api.*;
import cn.railway.foundation.security.Actor;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.*;

/** Control metadata only. No business payloads, source credentials or Kafka passwords. */
@RestController
@RequestMapping("/api/v1/kafka-subscriptions")
public class ReceiverAuthorizationController {
    private final SubscriptionService grants;private final ConsumptionService credentials;private final SubscriptionBroker broker;private final JdbcTemplate db;
    public ReceiverAuthorizationController(SubscriptionService grants,ConsumptionService credentials,SubscriptionBroker broker,JdbcTemplate db){this.grants=grants;this.credentials=credentials;this.broker=broker;this.db=db;}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Context(SubscriptionService.Grant grant,String clusterId,String bootstrapServers,String securityProtocol,String saslMechanism){}
    @PostMapping("/{id}/receiver-context")
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public ApiResponse<Context> workspace(@PathVariable String id){
        if(!Actor.demand())Actor.deny();lock(id);return ApiResponse.success(context(id));
    }
    @PostMapping("/{id}/receiver-access/check")
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public ApiResponse<Context> worker(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String authorization){
        lock(id);credentials.verifyCredential(id,authorization);return ApiResponse.success(context(id));
    }
    private void lock(String id){db.query("SELECT grant_id FROM kafka_subscription_grant WHERE grant_id=? FOR UPDATE",(r,n)->r.getString(1),id);}
    private Context context(String id){
        var grant=grants.get(id);
        if(!grant.brokerAccessConfirmed())throw new BusinessException(ErrorCode.ACCESS_FORBIDDEN,"订阅授权无效，不能接收数据");
        grants.requireEligible(grant);
        var cluster=db.queryForObject("SELECT cluster_id FROM kafka_subscription_execution WHERE grant_id=?",String.class,id);
        if(!broker.configuredCluster().equals(cluster))throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"Kafka集群配置不一致");
        return new Context(grant,cluster,broker.bootstrap(),broker.protocol(),"SCRAM-SHA-512");
    }
}
