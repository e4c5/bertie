package com.raditha.dedup.analysis;

import com.raditha.dedup.ai.GeminiAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AI-powered parameter naming using Gemini.
 * Falls back gracefully if AI is unavailable or fails.
 */
public class AIParameterNamer {

    private static final Logger logger = LoggerFactory.getLogger(AIParameterNamer.class);
    private static final int MAX_IDENTIFIER_LENGTH = 30;

    private final GeminiAIService aiService;
    private final boolean aiAvailable;

    /**
     * Creates a new AIParameterNamer.
     * Initializes the Gemini AI service if configuration is available.
     */
    public AIParameterNamer() {
        GeminiAIService service = null;
        try {
            service = new GeminiAIService();
            logger.info("AI parameter naming enabled");
        } catch (Exception e) {
            logger.debug("AI service not configured - will use pattern-based fallback: {}", e.getMessage());
        }
        this.aiService = service;
        this.aiAvailable = (service != null);
    }

    /**
     * Check if AI service is available.
     */
    public boolean isAvailable() {
        return aiAvailable;
    }

    /**
     * Suggest parameter name using AI.
     * Returns null if AI unavailable or fails.
     */
    public String suggestName(String literalValue, List<String> exampleValues, String usageContext) {
        if (!aiAvailable) {
            return null;
        }

        try {
            String prompt = buildPrompt(literalValue, exampleValues, usageContext);
            String response = aiService.sendApiRequest(prompt).trim();

            // Validate response is a valid Java identifier
            if (isValidJavaIdentifier(response)) {
                logger.debug("AI suggested parameter name '{}' for literal '{}'", response, literalValue);
                return response;
            } else {
                logger.debug("AI response '{}' is not a valid Java identifier", response);
            }
        } catch (Exception e) {
            logger.debug("AI naming failed, falling back to patterns: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Build prompt for AI to generate parameter name.
     */
    private String buildPrompt(String literal, List<String> examples, String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a short, descriptive Java parameter name for this value.\n\n");
        prompt.append("Literal: \"").append(literal).append("\"\n");

        if (examples != null && !examples.isEmpty()) {
            prompt.append("Example values: ");
            prompt.append(String.join(", ", examples.stream().limit(3).toList()));
            prompt.append("\n");
        }

        if (context != null && !context.isEmpty()) {
            prompt.append("Usage context: ").append(context).append("\n");
        }

        prompt.append("\nRules:\n");
        prompt.append("- Respond with ONLY the parameter name\n");
        prompt.append("- Use camelCase\n");
        prompt.append("- Be concise (1-2 words)\n");
        prompt.append("- Examples: reportTitle, emailAddress, configKey, queryString\n");
        prompt.append("\nParameter name:");

        return prompt.toString();
    }

    /**
     * Validate that a string is a valid Java identifier.
     */
    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_IDENTIFIER_LENGTH) {
            return false;
        }

        // Must start with lowercase letter
        if (!Character.isLowerCase(name.charAt(0))) {
            return false;
        }

        // Rest must be letters or digits
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isLetterOrDigit(name.charAt(i))) {
                return false;
            }
        }

        return true;
    }
}
