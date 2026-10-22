package org.stg.savan.savanserver.monitoring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports whether the configured Ollama server is reachable without exposing response details. */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final URI tagsUri;

    public OllamaHealthIndicator(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        this.tagsUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags");
    }

    @Override
    public Health health() {
        HttpRequest request = HttpRequest.newBuilder(tagsUri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up().build();
            }
            return Health.down().withDetail("reason", "Ollama returned a non-success status").build();
        } catch (IOException exception) {
            return Health.down().withDetail("reason", "Ollama is unreachable").build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("reason", "Ollama health check was interrupted").build();
        }
    }
}
