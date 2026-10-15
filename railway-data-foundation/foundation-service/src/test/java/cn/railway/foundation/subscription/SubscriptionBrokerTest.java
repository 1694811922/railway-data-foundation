package cn.railway.foundation.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import static org.assertj.core.api.Assertions.*;

class SubscriptionBrokerTest {
    final String user="rdfk_"+"a".repeat(32),group="rdf.subscription."+"b".repeat(32);
    @Test void exactTopicAndGroupOnlyWithoutWildcardOrWrite() {
        var bindings=SubscriptionBroker.bindings(user,"port.container.event.v1",group);
        assertThat(bindings).hasSize(3);
        assertThat(bindings).allSatisfy(b->{assertThat(b.pattern().patternType()).isEqualTo(PatternType.LITERAL);assertThat(b.entry().principal()).isEqualTo("User:"+user);assertThat(b.entry().permissionType()).isEqualTo(AclPermissionType.ALLOW);});
        assertThat(bindings.stream().map(b->b.entry().operation())).containsExactly(AclOperation.READ,AclOperation.DESCRIBE,AclOperation.READ);
        assertThat(bindings.get(2).pattern().resourceType()).isEqualTo(ResourceType.GROUP);
        assertThat(bindings.get(2).pattern().name()).isEqualTo(group);
    }
    @Test void neverAcceptSharedOrWildcardIdentityAndResources() {
        for(String value:new String[]{"*","admin",user+"x"})assertThatThrownBy(()->SubscriptionBroker.bindings(value,"topic",group)).isInstanceOf(IllegalStateException.class);
        for(String value:new String[]{"*",".","..","topic*"})assertThatThrownBy(()->SubscriptionBroker.bindings(user,value,group)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SubscriptionBroker.bindings(user,"topic","*")).isInstanceOf(IllegalStateException.class);
    }
    @Test void executionMustBeExplicitlyEnabledWithPinnedCluster() {
        var env=new MockEnvironment();var broker=new SubscriptionBroker(env);
        assertThat(broker.enabled()).isFalse();assertThatThrownBy(broker::configuredCluster).isInstanceOf(IllegalStateException.class);
        env.setProperty("KAFKA_SUBSCRIPTION_EXECUTION_ENABLED","true");
        assertThatThrownBy(broker::configuredCluster).isInstanceOf(IllegalStateException.class);
        env.setProperty("KAFKA_SUBSCRIPTION_CLUSTER_ID","cluster-test-id");assertThat(broker.configuredCluster()).isEqualTo("cluster-test-id");
    }
}
