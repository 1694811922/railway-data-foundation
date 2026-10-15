package cn.railway.foundation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FoundationApplication {
    public static void main(String[] args) {
        var context=SpringApplication.run(FoundationApplication.class, args);
        if(context.getEnvironment().getProperty("foundation.initialize-only",Boolean.class,false)){
            // The administrator initializer has already run; no permanent extra process or plaintext file.
            context.close();
        }
    }
}
