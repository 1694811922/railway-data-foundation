package cn.railway.example.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import java.net.URI;
import java.util.Properties;
import java.util.Set;

/** Broker credentials are separate from Foundation REST caller tokens. */
public final class KafkaCallerConfig {
    private KafkaCallerConfig() {}
    public static Properties create(String brokers,String protocol,String mechanism,String user,String password) {
        if(brokers==null||brokers.isBlank()||brokers.length()>1000)invalid();
        boolean local=true;
        for(String address:brokers.split(",",-1)){
            final URI uri;
            try{uri=URI.create("kafka://"+address);}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Broker地址不合法");}
            if(uri.getHost()==null||uri.getPort()<1||uri.getPort()>65535||!uri.getPath().isEmpty()||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)invalid();
            local&=Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost());
        }
        if(!Set.of("SASL_SSL","SASL_PLAINTEXT").contains(protocol==null?"":protocol)
                ||("SASL_PLAINTEXT".equals(protocol)&&!local)
                ||!Set.of("SCRAM-SHA-256","SCRAM-SHA-512","PLAIN").contains(mechanism==null?"":mechanism))invalid();
        if(user==null||!user.matches("[A-Za-z0-9_-]{1,120}")||password==null||password.length()<16||password.length()>256
                ||password.startsWith("rdf_")||password.chars().anyMatch(c->c<32||c==127))invalid();
        var config=new Properties();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,brokers);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,protocol);
        config.put(SaslConfigs.SASL_MECHANISM,mechanism);
        String module="PLAIN".equals(mechanism)?"org.apache.kafka.common.security.plain.PlainLoginModule":"org.apache.kafka.common.security.scram.ScramLoginModule";
        config.put(SaslConfigs.SASL_JAAS_CONFIG,module+" required username=\""+user+"\" password=\""+escape(password)+"\";");
        config.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG,3000);
        return config;
    }
    static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}
    private static void invalid(){throw new IllegalArgumentException("Kafka配置不合法：须明确Broker、独立凭据及SASL认证；远程必须使用TLS，不能使用REST调用令牌");}
}
