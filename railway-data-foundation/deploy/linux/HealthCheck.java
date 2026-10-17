import java.net.URI;
import java.net.http.*;
import java.time.Duration;
public final class HealthCheck {
    public static void main(String[] args) throws Exception {
        var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
            HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200||!response.body().matches("(?s).*\\\"code\\\"\\s*:\\s*0\\s*[,}].*"))System.exit(1);
    }
}
