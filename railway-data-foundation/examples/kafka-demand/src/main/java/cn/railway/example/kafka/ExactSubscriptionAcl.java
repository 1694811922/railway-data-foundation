package cn.railway.example.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Only exact Topic/Group read permissions. Does not approve Foundation sharing relationships. */
public final class ExactSubscriptionAcl {
    private ExactSubscriptionAcl() {}
    public static List<AclBinding> bindings(String username,String topic,String group) {
        if(username==null||!username.matches("[A-Za-z0-9_-]{1,120}")||!name(topic)||!name(group))throw new IllegalArgumentException("主体、主题与消费组须为明确名称，禁止通配符");
        return List.of(binding(username,ResourceType.TOPIC,topic,AclOperation.READ),
                binding(username,ResourceType.TOPIC,topic,AclOperation.DESCRIBE),
                binding(username,ResourceType.GROUP,group,AclOperation.READ));
    }
    public static void grant(Admin admin,String username,String topic,String group)throws Exception {
        admin.createAcls(bindings(username,topic,group)).all().get(5,TimeUnit.SECONDS);
    }
    public static void revoke(Admin admin,String username,String topic,String group)throws Exception {
        admin.deleteAcls(bindings(username,topic,group).stream().map(AclBinding::toFilter).toList()).all().get(5,TimeUnit.SECONDS);
    }
    private static AclBinding binding(String user,ResourceType type,String name,AclOperation operation){
        return new AclBinding(new ResourcePattern(type,name,PatternType.LITERAL),new AccessControlEntry("User:"+user,"*",operation,AclPermissionType.ALLOW));
    }
    static boolean name(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}")&&!value.equals(".")&&!value.equals("..");}
}
