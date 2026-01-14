package com.raditha.bertie.testbed.testisolation;

/**
 * Service with duplicate setup code that uses IMMUTABLE objects.
 *
 * Gap 6: These objects are SAFE to promote to instance fields.
 *
 * Expected behavior: String, Integer, and other immutables can be promoted safely.
 * This class demonstrates what Bertie SHOULD do (promote immutables).
 */
public class ServiceWithImmutableConfiguration {

    /**
     * Process API call with standard configuration.
     */
    public String processApiCall() {
        // Duplicate setup code - START (9 lines)
        String apiUrl = "https://api.example.com";
        String apiVersion = "v1";
        int timeout = 30;
        int retryCount = 3;
        boolean sslEnabled = true;
        boolean loggingEnabled = true;
        String authToken = "Bearer abc123";
        System.out.println("Configuring API client");
        System.out.println("URL: " + apiUrl + "/" + apiVersion);
        // Duplicate setup code - END

        return String.format("API: %s/%s, Timeout: %d, SSL: %b",
            apiUrl, apiVersion, timeout, sslEnabled);
    }

    /**
     * Calculate timeout value with standard configuration.
     */
    public int calculateTimeout() {
        // Duplicate setup code - START (9 lines)
        String apiUrl = "https://api.example.com";
        String apiVersion = "v1";
        int timeout = 30;
        int retryCount = 3;
        boolean sslEnabled = true;
        boolean loggingEnabled = true;
        String authToken = "Bearer abc123";
        System.out.println("Configuring API client");
        System.out.println("URL: " + apiUrl + "/" + apiVersion);
        // Duplicate setup code - END

        return timeout * retryCount;
    }

    /**
     * Validate security settings with standard configuration.
     */
    public boolean validateSecuritySettings() {
        // Duplicate setup code - START (9 lines)
        String apiUrl = "https://api.example.com";
        String apiVersion = "v1";
        int timeout = 30;
        int retryCount = 3;
        boolean sslEnabled = true;
        boolean loggingEnabled = true;
        String authToken = "Bearer abc123";
        System.out.println("Configuring API client");
        System.out.println("URL: " + apiUrl + "/" + apiVersion);
        // Duplicate setup code - END

        return sslEnabled && apiUrl.startsWith("https") && authToken.startsWith("Bearer");
    }
}
