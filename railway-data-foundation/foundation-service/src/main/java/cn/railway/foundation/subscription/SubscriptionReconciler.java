package cn.railway.foundation.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/** Single deployment prototype. Static Broker ACLs remain during control downtime; no instantaneous claim. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="KAFKA_SUBSCRIPTION_EXECUTION_ENABLED",havingValue="true")
public class SubscriptionReconciler {
    private final JdbcTemplate db;private final SubscriptionExecutor executor;
    public SubscriptionReconciler(JdbcTemplate db,SubscriptionExecutor executor){this.db=db;this.executor=executor;}
    @Scheduled(fixedDelayString="${KAFKA_SUBSCRIPTION_RECONCILE_MS:5000}",initialDelayString="${KAFKA_SUBSCRIPTION_RECONCILE_MS:5000}")
    public void reconcile() {
        try {
            var ids=db.query("SELECT grant_id FROM kafka_subscription_execution WHERE execution_state<>'REVOKED' ORDER BY checked_at,grant_id LIMIT 10",(r,n)->r.getString(1));
            for(String id:ids)try{executor.reconcile(id);}catch(RuntimeException ignored){/* Persisted intent is retried. Never log secret-bearing exceptions. */}
        }catch(RuntimeException ignored){/* Database unavailable: no fake success; reconcile after recovery. */}
    }
}
