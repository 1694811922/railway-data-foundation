package cn.railway.provider;

import org.springframework.core.env.Environment;
import java.net.URI;
import java.util.Properties;
import java.util.Set;

/** Backend-only connection settings. Never expose the returned properties in logs or API responses. */
public final class KafkaClientSettings {
    private KafkaClientSettings() {}
    public static Properties create(Environment env,String prefix,String authType) {
        String brokers=env.getProperty(prefix+"BOOTSTRAP_SERVERS","");
        String protocol=env.getProperty(prefix+"SECURITY_PROTOCOL","PLAINTEXT");
        if(brokers.isBlank() || brokers.length()>1000) throw invalid();
        boolean local=true;
        for(String address:brokers.split(",",-1)) {
            URI uri;
            try { uri=URI.create("kafka://"+address.strip()); } catch(IllegalArgumentException e){throw invalid();}
            if(uri.getHost()==null || uri.getPort()<1 || uri.getPort()>65535 || uri.getUserInfo()!=null
                    || !uri.getRawPath().isEmpty() || uri.getRawQuery()!=null || uri.getRawFragment()!=null) throw invalid();
            local &= Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        }
        var result=new Properties();result.put("bootstrap.servers",brokers);
        if("NONE".equals(authType)) {
            if(!"PLAINTEXT".equals(protocol) || !local) throw invalid();
        } else if("SASL".equals(authType)) {
            if(!Set.of("SASL_SSL","SASL_PLAINTEXT").contains(protocol) || ("SASL_PLAINTEXT".equals(protocol)&&!local)) throw invalid();
            String mechanism=env.getProperty(prefix+"SASL_MECHANISM","");
            String user=env.getProperty(prefix+"USERNAME","");
            String password=env.getProperty(prefix+"PASSWORD","");
            if(!Set.of("PLAIN","SCRAM-SHA-256","SCRAM-SHA-512").contains(mechanism)
                    || !user.matches("[A-Za-z0-9_-]{1,120}") || password.length()<16 || password.length()>256
                    || password.startsWith("rdf_") || password.chars().anyMatch(Character::isISOControl)) throw invalid();
            String module=mechanism.equals("PLAIN")?"plain.PlainLoginModule":"scram.ScramLoginModule";
            result.put("sasl.mechanism",mechanism);
            result.put("sasl.jaas.config","org.apache.kafka.common.security."+module+" required username=\""+escape(user)+"\" password=\""+escape(password)+"\";");
            // Standard JVM trust roots by default; a private CA may use a backend-only truststore.
            String truststore=env.getProperty(prefix+"SSL_TRUSTSTORE_LOCATION","");
            if(!truststore.isBlank()) {
                result.put("ssl.truststore.location",truststore);
                result.put("ssl.truststore.password",env.getProperty(prefix+"SSL_TRUSTSTORE_PASSWORD",""));
            }
        } else throw invalid();
        result.put("security.protocol",protocol);
        return result;
    }
    private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Kafka连接或认证配置不合法，请检查后端运维配置");}
}
