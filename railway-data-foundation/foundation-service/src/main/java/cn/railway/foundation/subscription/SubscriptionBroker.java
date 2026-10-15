package cn.railway.foundation.subscription;

import cn.railway.foundation.capability.KafkaClientSettings;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import org.apache.kafka.common.errors.ResourceNotFoundException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Metadata/identity/ACL adapter only: this service never subscribes to messages. */
@Component
public class SubscriptionBroker {
    private final Environment env;
    public SubscriptionBroker(Environment env) {this.env=env;}
    public boolean enabled() {return env.getProperty("KAFKA_SUBSCRIPTION_EXECUTION_ENABLED",Boolean.class,false);}
    public String configuredCluster() {
        String id=env.getProperty("KAFKA_SUBSCRIPTION_CLUSTER_ID","");
        if(!enabled()||!id.matches("[A-Za-z0-9_-]{10,100}"))throw unavailable();
        return id;
    }
    public String bootstrap() {return consumerSettings().getProperty("bootstrap.servers");}
    public String protocol() {return consumerSettings().getProperty("security.protocol");}
    private Properties consumerSettings() {return KafkaClientSettings.create(env,"FOUNDATION_KAFKA_","SASL");}
    public Session open(String pinnedCluster,boolean granting) {
        Admin admin=null;
        try {
            if(!configuredCluster().equals(pinnedCluster))throw unavailable();
            var config=KafkaClientSettings.create(env,"KAFKA_SUBSCRIPTION_ADMIN_","SASL");
            if(env.getProperty("KAFKA_SUBSCRIPTION_ADMIN_USERNAME","").equals(env.getProperty("FOUNDATION_KAFKA_USERNAME","")))throw unavailable();
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,2000);config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,3000);
            admin=Admin.create(config);
            if(!pinnedCluster.equals(admin.describeCluster().clusterId().get(4,TimeUnit.SECONDS)))throw unavailable();
            if(granting) {
                var probeConfig=consumerSettings();probeConfig.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,2000);probeConfig.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,3000);
                try(Admin probe=Admin.create(probeConfig)) {
                    if(!pinnedCluster.equals(probe.describeCluster().clusterId().get(4,TimeUnit.SECONDS)))throw unavailable();
                }
            }
            return new Session(admin);
        }catch(Exception e){if(admin!=null)admin.close(Duration.ofMillis(200));if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}
    }
    public final class Session implements AutoCloseable {
        private final Admin admin;
        private Session(Admin admin){this.admin=admin;}
        private Collection<AclBinding> actual(String user)throws Exception {
            return admin.describeAcls(new AclBindingFilter(ResourcePatternFilter.ANY,new AccessControlEntryFilter("User:"+user,null,AclOperation.ANY,AclPermissionType.ANY))).values().get(4,TimeUnit.SECONDS);
        }
        private List<ScramCredentialInfo> credentials(String user)throws Exception {
            try{return admin.describeUserScramCredentials(List.of(user)).description(user).get(4,TimeUnit.SECONDS).credentialInfos();}
            catch(ExecutionException e){if(e.getCause() instanceof ResourceNotFoundException)return List.of();throw e;}
        }
        public void grant(String user,String password,String topic,String group)throws Exception {
            var expected=bindings(user,topic,group);var current=actual(user);
            if(!new HashSet<>(expected).containsAll(current))throw unavailable();
            var info=admin.describeTopics(List.of(topic)).allTopicNames().get(4,TimeUnit.SECONDS).get(topic);
            if(info==null||info.isInternal()||info.partitions().isEmpty()||info.partitions().stream().anyMatch(p->p.leader()==null))throw unavailable();
            if(password==null)throw unavailable();
            byte[] bytes=password.getBytes(StandardCharsets.UTF_8);
            try {admin.alterUserScramCredentials(List.of(new UserScramCredentialUpsertion(user,new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512,4096),bytes))).all().get(4,TimeUnit.SECONDS);}
            finally {Arrays.fill(bytes,(byte)0);}
            admin.createAcls(expected).all().get(4,TimeUnit.SECONDS);check(user,topic,group);
        }
        public void check(String user,String topic,String group)throws Exception {
            if(!new HashSet<>(actual(user)).equals(new HashSet<>(bindings(user,topic,group))))throw unavailable();
            var credentials=credentials(user);
            if(credentials.size()!=1||credentials.get(0).mechanism()!=ScramMechanism.SCRAM_SHA_512)throw unavailable();
        }
        public void revoke(String user)throws Exception {
            requireIdentity(user);
            // An identity belongs exclusively to one grant generation; remove all its partial permissions.
            var all=actual(user);
            if(!all.isEmpty())admin.deleteAcls(all.stream().map(AclBinding::toFilter).toList()).all().get(4,TimeUnit.SECONDS);
            var credentials=credentials(user);
            if(!credentials.isEmpty())admin.alterUserScramCredentials(credentials.stream().<UserScramCredentialAlteration>map(info->new UserScramCredentialDeletion(user,info.mechanism())).toList()).all().get(4,TimeUnit.SECONDS);
            if(!actual(user).isEmpty()||!credentials(user).isEmpty())throw unavailable();
        }
        public void close(){admin.close(Duration.ofMillis(200));}
    }
    public static List<AclBinding> bindings(String user,String topic,String group) {
        requireIdentity(user);
        if(topic==null||!topic.matches("[A-Za-z0-9._-]{1,249}")||Set.of(".","..").contains(topic)||group==null||!group.matches("rdf\\.subscription\\.[a-f0-9]{32}"))throw unavailable();
        return List.of(binding(user,ResourceType.TOPIC,topic,AclOperation.READ),binding(user,ResourceType.TOPIC,topic,AclOperation.DESCRIBE),binding(user,ResourceType.GROUP,group,AclOperation.READ));
    }
    private static AclBinding binding(String user,ResourceType type,String name,AclOperation op){return new AclBinding(new ResourcePattern(type,name,PatternType.LITERAL),new AccessControlEntry("User:"+user,"*",op,AclPermissionType.ALLOW));}
    private static void requireIdentity(String user){if(user==null||!user.matches("rdfk_[a-f0-9]{32}"))throw unavailable();}
    public static IllegalStateException unavailable(){return new IllegalStateException("Kafka权限执行或集群核对失败，请检查服务端配置与Broker后重试");}
}
