package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.ai.GeminiAIService;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;

import java.util.HashSet;
import java.util.Set;

/**
 * Generates unique method names using three strategies:
 * 1. Sequential numbering (extractedMethod1, extractedMethod2, ...)
 * 2. Semantic analysis (based on code content)
 * 3. AI-powered (using Gemini AI)
 */
public class MethodNameGenerator {

    /**
     * Strategy for generating method names.
     */
    public enum NamingStrategy {
        SEQUENTIAL, // Simple numbering
        SEMANTIC, // Code analysis
        AI_POWERED // Gemini AI
    }

    private int methodCounter = 0;
    private final SemanticNameAnalyzer semanticAnalyzer;
    private GeminiAIService aiService;
    private final boolean useAI;
    private final Set<String> generatedNames = new HashSet<>(); // Track names generated in this session

    /**
     * Creates a generator with AI enabled by default.
     */
    public MethodNameGenerator() {
        this(true); // AI enabled by default
    }

    /**
     * Creates a generator with specific AI setting.
     *
     * @param useAI true to enable AI naming, false for deterministic only
     */
    public MethodNameGenerator(boolean useAI) {
        this.semanticAnalyzer = new SemanticNameAnalyzer();
        this.useAI = useAI;

        if (useAI) {
            try {
                this.aiService = new GeminiAIService();
            } catch (Exception e) {
                // AI service not available, will fall back to semantic/sequential
                this.aiService = null;
            }
        }
    }

    /**
     * Generate a unique method name for the refactoring.
     * Falls back through strategies: AI → Semantic → Sequential
     */
    public String generateName(
            DuplicateCluster cluster,
            RefactoringStrategy strategy,
            ClassOrInterfaceDeclaration containingClass,
            NamingStrategy preferredStrategy,
            String inferredReturnVariable) {

        String baseName = switch (strategy) {
            case EXTRACT_TO_PARAMETERIZED_TEST -> "test";
            case EXTRACT_TO_UTILITY_CLASS -> "extractedUtility";
            default -> null; // Will generate name
        };

        // For non-helper method strategies, use predefined names
        if (baseName != null) {
            return ensureUnique(baseName, containingClass);
        }

        // Try preferred strategy first
        String name = tryStrategy(preferredStrategy, cluster.primary(), containingClass);
        if (name != null) {
            return name;
        }

        // Try naming based on return variable
        if (inferredReturnVariable != null && !inferredReturnVariable.isEmpty()) {
             String candidate = "get" + semanticAnalyzer.capitalize(inferredReturnVariable);
             return ensureUnique(candidate, containingClass);
        }

        // Fallback chain: AI → Semantic → Sequential
        if (preferredStrategy != NamingStrategy.AI_POWERED && useAI && aiService != null) {
            name = tryAIName(cluster.primary(), containingClass);
            if (name != null) {
                return name;
            }
        }

        if (preferredStrategy != NamingStrategy.SEMANTIC) {
            name = trySemanticName(cluster.primary(), containingClass);
            if (name != null) {
                return name;
            }
        }

        // Last resort: sequential
        return generateSequentialName(containingClass);
    }


    private String tryStrategy(NamingStrategy strategy, StatementSequence sequence,
            ClassOrInterfaceDeclaration containingClass) {
        return switch (strategy) {
            case SEQUENTIAL -> generateSequentialName(containingClass);
            case SEMANTIC -> trySemanticName(sequence, containingClass);
            case AI_POWERED -> tryAIName(sequence, containingClass);
        };
    }

    /**
     * Generate sequential name: extractedMethod1, extractedMethod2, ...
     */
    private String generateSequentialName(ClassOrInterfaceDeclaration containingClass) {
        String baseName = "extractedMethod";
        methodCounter++;
        return ensureUnique(baseName + methodCounter, containingClass);
    }

    /**
     * Try to generate semantic name based on code analysis.
     */
    private String trySemanticName(StatementSequence sequence, ClassOrInterfaceDeclaration containingClass) {
        String name = semanticAnalyzer.generateName(sequence);
        if (name != null && !name.isEmpty()) {
            return ensureUnique(name, containingClass);
        }
        return null;
    }

    /**
     * Try to generate name using AI.
     */
    private String tryAIName(StatementSequence sequence, ClassOrInterfaceDeclaration containingClass) {
        if (aiService == null || !useAI) {
            return null;
        }

        try {
            String code = sequence.statements().toString();

            // Limit code length for API call
            if (code.length() > 500) {
                code = code.substring(0, 500) + "...";
            }

            String prompt = buildNamingPrompt(code);
            String aiResponse = callAIService(prompt);

            if (aiResponse != null && !aiResponse.isEmpty()) {
                // Clean up AI response (remove quotes, whitespace, etc.)
                String cleaned = cleanAIResponse(aiResponse);
                if (isValidMethodName(cleaned)) {
                    return ensureUnique(cleaned, containingClass);
                }
            }
        } catch (Exception e) {
            // AI failed, will fall back to other strategies
            return null;
        }

        return null;
    }

    private String buildNamingPrompt(String code) {
        return "Suggest a concise Java method name for this code snippet. " +
                "Return ONLY the method name in camelCase, no explanation, no quotes, no punctuation.\n\n" +
                "Code:\n" + code + "\n\nMethod name:";
    }

    private String callAIService(String prompt) {
        if (aiService == null) {
            return null;
        }

        try {
            // Build Gemini API request payload
            String payload = buildGeminiPayload(prompt);

            // Call Gemini API
            String responseBody = aiService.sendApiRequest(payload);

            // Extract text from response
            return extractTextFromGeminiResponse(responseBody);
        } catch (Exception e) {
            // AI service failed - fall back to semantic/sequential naming
            return null;
        }
    }

    /**
     * Build Gemini API payload for method naming request.
     */
    private String buildGeminiPayload(String userPrompt) {
        // Escape JSON strings
        String escapedPrompt = escapeJsonString(userPrompt);

        return String.format("""
                {
                  "contents": [
                    {
                      "role": "user",
                      "parts": [
                        { "text": "%s" }
                      ]
                    }
                  ],
                  "generationConfig": {
                    "temperature": 0.3,
                    "maxOutputTokens": 50
                  }
                }
                """, escapedPrompt);
    }

    /**
     * Extract text response from Gemini API JSON response.
     */
    private String extractTextFromGeminiResponse(String responseBody) {
        try {
            // Simple JSON parsing to extract text
            // Response format: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
            int textStart = responseBody.indexOf("\"text\":");
            if (textStart == -1) {
                return null;
            }

            textStart += 8; // Skip "text":"
            int textEnd = responseBody.indexOf("\"", textStart);

            if (textEnd == -1) {
                return null;
            }

            return responseBody.substring(textStart, textEnd);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Escape string for JSON format.
     */
    private String escapeJsonString(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String cleanAIResponse(String response) {
        if (response == null) {
            return "";
        }

        // Remove quotes, whitespace, and common artifacts
        return response.trim()
                .replaceAll("^[\"']|[\"']$", "") // Remove surrounding quotes
                .replaceAll("[^a-zA-Z0-9_]", "") // Keep only valid identifier chars
                .replaceAll("^\\d+", ""); // Remove leading digits
    }

    private boolean isValidMethodName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        // Must start with lowercase letter
        if (!Character.isLowerCase(name.charAt(0))) {
            return false;
        }

        // Must be valid Java identifier
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }

        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }

        // Reasonable length
        return name.length() >= 3 && name.length() <= 40;
    }

    /**
     * Ensure name is unique in the class AND not already generated in this session.
     */
    private String ensureUnique(String baseName, ClassOrInterfaceDeclaration containingClass) {
        if (containingClass == null) {
            return baseName;
        }

        Set<String> existingNames = getExistingMethodNames(containingClass);
        
        // Combine existing names with names generated in this session
        existingNames.addAll(generatedNames);

        if (!existingNames.contains(baseName)) {
            generatedNames.add(baseName); // Track this name
            return baseName;
        }

        // Append number until unique
        int suffix = 1;
        String uniqueName;
        do {
            uniqueName = baseName + suffix;
            suffix++;
        } while (existingNames.contains(uniqueName));

        generatedNames.add(uniqueName); // Track this name
        return uniqueName;
    }

    private Set<String> getExistingMethodNames(ClassOrInterfaceDeclaration clazz) {
        Set<String> names = new HashSet<>();

        for (MethodDeclaration method : clazz.getMethods()) {
            names.add(method.getNameAsString());
        }

        return names;
    }
}
