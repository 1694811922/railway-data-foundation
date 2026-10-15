package cn.railway.provider;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.time.*;

/** The demo Source DATETIME has no offset column; explicitly interpret it using a fixed offset. */
@Component
public class SourceTime {
    private final ZoneOffset offset;
    public SourceTime(Environment environment){
        try{
            offset=ZoneOffset.of(environment.getProperty("PROVIDER_SOURCE_TIME_OFFSET","+08:00"));
            int seconds=offset.getTotalSeconds();
            if(seconds < -13*3600-59*60 || seconds > 14*3600 || seconds%60!=0)throw new IllegalArgumentException();
        }catch(DateTimeException|IllegalArgumentException ex){throw new IllegalStateException("业务源固定时区偏移配置不合法");}
    }
    public LocalDateTime local(Instant instant){return LocalDateTime.ofInstant(instant,offset);}
    public ZoneOffset offset(){return offset;}
}
