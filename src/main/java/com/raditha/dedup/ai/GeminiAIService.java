package com.raditha.dedup.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Lightweight AI service for Bertie - provides only the Gemini API call
 * functionality
 * needed for method naming, without the query optimization features.
 * <p>
 * Based on GeminiAIService from antikythera-examples but simplified to only
 * include
 * the sendApiRequest method that MethodNameGenerator actually uses.
 */
public class GeminiAIService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiAIService.class);

    private HttpClient httpClient;
    private Map<String, Object> config;

    public GeminiAIService() throws IOException {
        // Load configuration from Settings if available
        try {
            Object aiConfig = Settings.getProperty("ai_service");
            if (aiConfig instanceof Map) {
                this.config = (Map<String, Object>) aiConfig;
            }
        } catch (Exception e) {
            // Settings not available or not configured
            logger.debug("AI service configuration not found in Settings");
        }

        // Validate API key is available - fail fast if not
        String apiKey = getConfigString("api_key", null);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException(
                    "AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }

        int timeoutSeconds = getConfigInt("timeout_seconds", 60);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * Sends an API request to Gemini AI service.
     * This is the only public method from the original GeminiAIService that
     * MethodNameGenerator actually uses.
     */
    public String sendApiRequest(String payload) throws IOException, InterruptedException {
        String apiEndpoint = getConfigString("api_endpoint",
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent");
        String model = getConfigString("model", "gemini-2.0-flash-exp");
        String apiKey = getConfigString("api_key", null);
        int timeoutSeconds = getConfigInt("timeout_seconds", 60);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }

        String url = apiEndpoint.replace("{model}", model) + "?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                    "API request failed with status: " + response.statusCode() + ", body: " + response.body());
        }

        return response.body();
    }

    /**
     * Gets a string configuration value with fallback to environment variables.
     */
    private String getConfigString(String key, String defaultValue) {
        if (config == null)
            return getEnvironmentFallback(key, defaultValue);

        Object value = config.get(key);
        if (value instanceof String str && !str.trim().isEmpty()) {
            return str;
        }

        return getEnvironmentFallback(key, defaultValue);
    }

    private String getEnvironmentFallback(String key, String defaultValue) {
        // Fallback to environment variables
        if ("api_key".equals(key)) {
            String envValue = System.getenv("GEMINI_API_KEY");
            if (envValue != null && !envValue.trim().isEmpty()) {
                return envValue;
            }
        } else if ("api_endpoint".equals(key)) {
            String envValue = System.getenv("AI_SERVICE_ENDPOINT");
            if (envValue != null && !envValue.trim().isEmpty()) {
                return envValue;
            }
        }

        return defaultValue;
    }

    /**
     * Gets an integer configuration value.
     */
    private int getConfigInt(String key, int defaultValue) {
        if (config == null)
            return defaultValue;

        Object value = config.get(key);
        if (value instanceof Integer i) {
            return i;
        } else if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
