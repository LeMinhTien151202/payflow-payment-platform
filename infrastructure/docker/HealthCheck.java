import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Dependency-free container health probe executed with Java's source-file launcher. */
public final class HealthCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.exit(2);
        }
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        var request = HttpRequest.newBuilder(URI.create(args[0]))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        boolean healthy = response.statusCode() == 200
                && response.body().contains("\"status\":\"UP\"");
        System.exit(healthy ? 0 : 1);
    }
}
