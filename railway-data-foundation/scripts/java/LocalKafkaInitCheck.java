import kafka.server.KafkaConfig;
import kafka.server.KafkaRaftServer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.acl.AclOperation;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fresh native cluster checks, isolated from every running platform process. */
public final class LocalKafkaInitCheck {
    static Throwable root(Throwable error) {while(error.getCause()!=null&&error.getCause()!=error)error=error.getCause();return error;}
    static void save(Path path,Properties properties)throws Exception {try(var writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8)){properties.store(writer,"Private bootstrap test fixture");}}
    static String secret() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    public static void main(String[] args) {
        KafkaRaftServer server=null;int exitCode=0;Path result=null;var evidence=new Properties();
        try {
            Path cwd=Path.of("").toAbsolutePath().normalize();
            if(!Files.exists(cwd.resolve("identity.properties"))||!Files.exists(cwd.resolve("server.properties")))throw new IllegalStateException("Run inside the private Kafka runtime");
            if(args.length!=1||!args[0].matches("bootstrap-result-[a-f0-9]{32}\\.properties"))throw new IllegalArgumentException("Invalid evidence filename");
            result=Path.of(args[0]);if(Files.exists(result))throw new IllegalStateException("Existing evidence must not be overwritten");
            String name="initialization-check-"+UUID.randomUUID().toString().replace("-","");Path fixture=Path.of(name);
            Files.createDirectory(fixture);
            var identity=new Properties();identity.setProperty("topic","initialization.check."+UUID.randomUUID().toString().replace("-",""));
            int port,controller;
            try(var first=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var second=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {port=first.getLocalPort();controller=second.getLocalPort();}
            identity.setProperty("port",Integer.toString(port));identity.setProperty("controller.port",Integer.toString(controller));
            for(String role:List.of("broker","controller","admin","probe","producer")) {identity.setProperty(role+".username","init_"+role);identity.setProperty(role+".password",secret());}
            save(fixture.resolve("identity.properties"),identity);
            var config=LocalKafka.load("server.properties");
            config.setProperty("log.dirs",name+"/data");config.setProperty("metadata.log.dir",name+"/data");
            config.setProperty("metadata.log.segment.bytes","8388608");
            config.setProperty("listeners","BROKER://127.0.0.1:"+port+",CONTROLLER://127.0.0.1:"+controller);
            config.setProperty("advertised.listeners","BROKER://127.0.0.1:"+port);
            config.setProperty("controller.quorum.voters","1@127.0.0.1:"+controller);
            config.setProperty("super.users","User:init_broker;User:init_controller;User:init_admin");
            config.setProperty("listener.name.broker.scram-sha-512.sasl.jaas.config","org.apache.kafka.common.security.scram.ScramLoginModule required username=\"init_broker\" password=\""+identity.getProperty("broker.password")+"\";");
            config.setProperty("listener.name.broker.plain.sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\"init_broker\" password=\""+identity.getProperty("broker.password")+"\" user_init_broker=\""+identity.getProperty("broker.password")+"\" user_init_admin=\""+identity.getProperty("admin.password")+"\";");
            config.setProperty("listener.name.controller.plain.sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\"init_controller\" password=\""+identity.getProperty("controller.password")+"\" user_init_controller=\""+identity.getProperty("controller.password")+"\";");
            Path configFile=fixture.resolve("server.properties"),meta=fixture.resolve("data/meta.properties");save(configFile,config);
            LocalKafka.format(identity,configFile.toString());
            server=new KafkaRaftServer(KafkaConfig.fromProps(config),Time.SYSTEM);server.startup();
            LocalKafka.setup(identity,meta.toString(),true);
            Path overlay=fixture.resolve("backend.env");byte[] before=Files.readAllBytes(overlay);
            LocalKafka.setup(identity,meta.toString(),false);
            if(!Arrays.equals(before,Files.readAllBytes(overlay)))throw new IllegalStateException("Verification rewrote overlay");
            System.out.println("Fresh native cluster initialized and read-only readiness passed");
            evidence.setProperty("fresh.initialization","true");
            var wrong=new Properties();wrong.putAll(identity);wrong.setProperty("probe.password",secret());boolean denied=false;
            try {LocalKafka.setup(wrong,meta.toString(),false);}catch(Exception error) {if(root(error) instanceof AuthenticationException)denied=true;else throw error;}
            if(!denied)throw new IllegalStateException("Wrong static password accepted");
            LocalKafka.setup(identity,meta.toString(),false);
            System.out.println("Wrong static password refused; existing credential not overwritten");
            evidence.setProperty("wrong.password.rejected","true");
            try(Admin admin=Admin.create(LocalKafka.client(identity,"admin"))) {
                var expected=LocalKafka.acl(identity.getProperty("probe.username"),ResourceType.TOPIC,identity.getProperty("topic"),AclOperation.DESCRIBE);
                admin.deleteAcls(List.of(expected.toFilter())).all().get(5,TimeUnit.SECONDS);
                boolean rejected=false;try {LocalKafka.setup(identity,meta.toString(),false);}catch(IllegalStateException error) {rejected=true;}
                if(!rejected||!admin.describeAcls(expected.toFilter()).values().get(5,TimeUnit.SECONDS).isEmpty())throw new IllegalStateException("Readiness recreated missing ACL");
                admin.createAcls(List.of(expected)).all().get(5,TimeUnit.SECONDS);
                LocalKafka.setup(identity,meta.toString(),false);
            }
            System.out.println("Missing ACL refused without silent recreation");
            evidence.setProperty("missing.acl.rejected","true");
            Path saved=fixture.resolve("backend.env.saved");Files.move(overlay,saved);
            boolean refused=false;try {LocalKafka.setup(identity,meta.toString(),false);}catch(LocalKafka.MissingRuntimeOverlayException expected) {refused=true;}
            if(!refused||Files.exists(overlay))throw new IllegalStateException("Missing overlay recreated");
            Files.move(saved,overlay);
            System.out.println("Missing overlay refused without credential reissue");
            evidence.setProperty("missing.overlay.rejected","true");evidence.setProperty("fixture",name);
            System.out.println("Bootstrap fixture retained privately: "+name+"; test cluster will be stopped");
        }catch(Throwable error) {System.err.println("Bootstrap check failed ("+root(error).getClass().getSimpleName()+"); private evidence retained.");exitCode=1;}
        finally {if(server!=null) {server.shutdown();server.awaitShutdown();evidence.setProperty("test.broker.stopped","true");}}
        if(exitCode==0)try {save(result,evidence);}catch(Exception error) {System.err.println("Bootstrap evidence could not be saved");exitCode=1;}
        if(exitCode!=0)System.exit(exitCode);
    }
}
