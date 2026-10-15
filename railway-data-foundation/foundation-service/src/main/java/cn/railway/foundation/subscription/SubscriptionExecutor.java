package cn.railway.foundation.subscription;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.capability.CapabilityService;
import cn.railway.foundation.capability.KafkaCapabilityVerifier;
import cn.railway.foundation.credential.EncryptedCredentialService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Persist intent before any Broker change. Interrupted/failed work remains recoverable and visible. */
@Service
public class SubscriptionExecutor {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Delivery(SubscriptionService.Grant grant,String bootstrapServers,String securityProtocol,
                           String saslMechanism,String username,String password,String topic,String consumerGroup,String consumptionToken) {
        @Override public String toString(){return "Subscription credential [REDACTED]";}
    }
    private record Execution(String username,String cluster,String desired,String state,String encrypted) {
        @Override public String toString(){return "Subscription execution [REDACTED]";}
    }
    private final JdbcTemplate db;
    private final SubscriptionService grants;
    private final SubscriptionBroker broker;
    private final EncryptedCredentialService secrets;
    private final KafkaCapabilityVerifier verifier;
    private final CapabilityService capabilities;
    private final TransactionTemplate tx;
    private final ConsumptionService consumption;
    public SubscriptionExecutor(JdbcTemplate db,SubscriptionService grants,SubscriptionBroker broker,EncryptedCredentialService secrets,
                                KafkaCapabilityVerifier verifier,CapabilityService capabilities,PlatformTransactionManager manager,ConsumptionService consumption) {
        this.db=db;this.grants=grants;this.broker=broker;this.secrets=secrets;this.verifier=verifier;this.capabilities=capabilities;this.tx=new TransactionTemplate(manager);this.consumption=consumption;
    }
    public SubscriptionService.Grant request(String id,String operation,SubscriptionExecutionRequest request) {
        tx.executeWithoutResult(status->{
            var grant=grants.locked(id,request.version());var current=execution(id);
            if("provision".equals(operation)) {
                String cluster;
                try{cluster=broker.configuredCluster();}catch(RuntimeException e){throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"Kafka订阅执行未配置，请配置独立管理员及集群编号");}
                requireEligible(grant);
                if(current!=null&&!"REVOKED".equals(current.state()))invalid("已有权限执行记录，请先停止并核对撤权；失败记录可重试处理");
                String username="rdfk_"+UUID.randomUUID().toString().replace("-","");
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
                String password="ks_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Arrays.fill(bytes,(byte)0);
                String encrypted=secrets.sealSubscription(id,username,password);
                db.update("INSERT INTO kafka_subscription_execution(grant_id,broker_username,cluster_id,desired_state,execution_state,encrypted_password,updated_at) VALUES(?,?,?,'GRANTED','PENDING',?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE broker_username=VALUES(broker_username),cluster_id=VALUES(cluster_id),desired_state='GRANTED',execution_state='PENDING',encrypted_password=VALUES(encrypted_password),issued_at=NULL,consumption_token_digest=NULL,last_error='',checked_at=NULL,updated_at=UTC_TIMESTAMP(3)",id,username,cluster,encrypted);
                grants.executionEvent(grant,"ACCESS_REQUEST",request.reason());
            }else if("stop".equals(operation)) {
                if(current==null||"REVOKED".equals(current.state()))invalid("该申请没有待停止的订阅身份");
                db.update("UPDATE kafka_subscription_execution SET desired_state='REVOKED',execution_state='REVOKE_PENDING',last_error='',updated_at=UTC_TIMESTAMP(3) WHERE grant_id=?",id);
                grants.executionEvent(grant,"STOP_REQUEST",request.reason());
            }else if("retry".equals(operation)) {
                if(current==null||!Set.of("PENDING","FAILED","REVOKE_PENDING").contains(current.state()))invalid("没有需要重试的执行记录");
                grants.executionEvent(grant,"RETRY_REQUEST",request.reason());
            }else invalid("不支持该执行操作");
        });
        // Separate transaction: database commit is not presented as a Broker acknowledgement.
        reconcile(id);
        return grants.get(id);
    }
    public void reconcile(String id) {
        tx.executeWithoutResult(transaction->{
            db.query("SELECT grant_id FROM kafka_subscription_grant WHERE grant_id=? FOR UPDATE",(r,n)->r.getString(1),id);
            var grant=grants.get(id);var execution=execution(id);
            if(execution==null||"REVOKED".equals(execution.state()))return;
            boolean eligible=true;
            try{requireEligible(grant);}catch(RuntimeException e){eligible=false;}
            boolean revoke=!eligible||"REVOKED".equals(execution.desired());
            if(revoke)db.update("UPDATE kafka_subscription_execution SET desired_state='REVOKED' WHERE grant_id=?",id);
            String result=null,desired=null,error="",reason="";boolean clear=false;
            try(var session=broker.open(execution.cluster(),!revoke)) {
                if(revoke) {
                    session.revoke(execution.username());
                    result="REVOKED";desired="REVOKED";clear=true;reason=!eligible?"治理条件失效，实际移除身份和权限":"停止订阅，实际移除身份和权限";
                }else if("ACTIVE".equals(execution.state())) {
                    session.check(execution.username(),grant.topic(),grant.consumerGroup());
                }else {
                    session.grant(execution.username(),secrets.openSubscription(id,execution.username(),execution.encrypted()),grant.topic(),grant.consumerGroup());
                    result="ACTIVE";desired="GRANTED";reason="实际核对专属SCRAM身份与精确主题/消费组权限";
                }
            }catch(Exception e) {
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                // Any partial grant is conservatively compensated, never automatically widened/re-enabled.
                result="FAILED";desired="REVOKED";error="权限状态未确认，待恢复Broker后重试撤权";reason="Kafka执行失败；未确认成功，将重试撤权";
            }
            // Database failures escape the transaction: an external change is repaired from the committed intent.
            if(result==null)db.update("UPDATE kafka_subscription_execution SET checked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE grant_id=?",id);
            else setState(grant,result,desired,clear,error,reason);
        });
    }
    public Delivery claim(String id,SubscriptionExecutionRequest request) {
        return tx.execute(transaction->{
            var grant=grants.locked(id,request.version());requireEligible(grant);var execution=execution(id);
            if(execution==null||!"ACTIVE".equals(execution.state())||execution.encrypted()==null)invalid("凭据不可领取或已领取；遗失后须停止订阅并重新配置身份");
            try(var session=broker.open(execution.cluster(),true)){session.check(execution.username(),grant.topic(),grant.consumerGroup());}
            catch(Exception e){throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"Broker权限未确认，暂不能领取凭据");}
            String password=secrets.openSubscription(id,execution.username(),execution.encrypted());
            // Build non-secret connection fields before the one-time clear; bad configuration rolls back the clear.
            String bootstrap=broker.bootstrap(),protocol=broker.protocol();
            db.update("UPDATE kafka_subscription_execution SET encrypted_password=NULL,issued_at=UTC_TIMESTAMP(3),checked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE grant_id=?",id);
            String consumptionToken=consumption.issue(id);
            grants.executionEvent(grant,"CREDENTIAL_ISSUED",request.reason());
            return new Delivery(grants.get(id),bootstrap,protocol,"SCRAM-SHA-512",execution.username(),password,grant.topic(),grant.consumerGroup(),consumptionToken);
        });
    }
    private void requireEligible(SubscriptionService.Grant grant) {
        grants.requireEligible(grant);
        verifier.requireConfigured(capabilities.get(grant.capabilityId()));
    }
    private Execution execution(String id) {
        var rows=db.query("SELECT broker_username,cluster_id,desired_state,execution_state,encrypted_password FROM kafka_subscription_execution WHERE grant_id=?",(r,n)->new Execution(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private void setState(SubscriptionService.Grant grant,String state,String desired,boolean clear,String error,String reason) {
        String before=db.queryForObject("SELECT execution_state FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant.grantId());
        db.update("UPDATE kafka_subscription_execution SET execution_state=?,desired_state=?,last_error=?,encrypted_password=CASE WHEN ? THEN NULL ELSE encrypted_password END,checked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE grant_id=?",state,desired,error,clear,grant.grantId());
        if(!state.equals(before))grants.executionEvent(grant,"ACTIVE".equals(state)?"ACCESS_GRANTED":"REVOKED".equals(state)?"ACCESS_REVOKED":"EXECUTION_FAILED",reason);
    }
    private static void invalid(String message){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,message);}
}
