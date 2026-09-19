package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.ai.GeminiAIService;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MethodNameGeneratorGeminiParsingTest {

    /** Representative Gemini generateContent response including metadata the parser must skip. */
    private static final String GEMINI_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "validateOrderTotals\\n" }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP",
                  "index": 0,
                  "safetyRatings": [
                    { "category": "HARM_CATEGORY_HATE_SPEECH", "probability": "NEGLIGIBLE" }
                  ]
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 120,
                "candidatesTokenCount": 4,
                "totalTokenCount": 124
              },
              "modelVersion": "gemini-1.5-flash"
            }
            """;

    @Test
    void extractsCandidateText() {
        assertEquals("validateOrderTotals\n",
                MethodNameGenerator.extractTextFromGeminiResponse(GEMINI_RESPONSE));
    }

    @Test
    void handlesEscapedQuotesAndUnicodeInsideText() {
        String body = """
                {"candidates":[{"content":{"parts":[{"text":"say \\"hi\\" \\u00e9 done"}]}}]}
                """;
        assertEquals("say \"hi\" é done", MethodNameGenerator.extractTextFromGeminiResponse(body));
    }

    @Test
    void ignoresTextKeysOutsideTheCandidatePath() {
        String body = """
                {"promptFeedback":{"text":"not this"},
                 "candidates":[{"content":{"parts":[{"text":"computeTotal"}]}}]}
                """;
        assertEquals("computeTotal", MethodNameGenerator.extractTextFromGeminiResponse(body));
    }

    @Test
    void returnsNullForMalformedOrIncompleteResponses() {
        assertNull(MethodNameGenerator.extractTextFromGeminiResponse(null));
        assertNull(MethodNameGenerator.extractTextFromGeminiResponse(""));
        assertNull(MethodNameGenerator.extractTextFromGeminiResponse("{not json"));
        assertNull(MethodNameGenerator.extractTextFromGeminiResponse("{\"candidates\":[]}"));
        assertNull(MethodNameGenerator.extractTextFromGeminiResponse(
                "{\"error\":{\"code\":429,\"message\":\"quota exceeded\"}}"));
        assertNull(MethodNameGenerator.extractTextFromGeminiResponse(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":42}]}}]}"));
    }

    @Test
    void aiNameIsUsedWhenResponseIsValid() throws IOException, InterruptedException {
        GeminiAIService service = mock(GeminiAIService.class);
        when(service.sendApiRequest(anyString())).thenReturn(GEMINI_RESPONSE);

        String name = generate(new MethodNameGenerator(service));

        assertEquals("validateOrderTotals", name);
    }

    @Test
    void fallsBackToSemanticThenSequentialWhenAiResponseIsUnparseable() throws IOException, InterruptedException {
        GeminiAIService service = mock(GeminiAIService.class);
        when(service.sendApiRequest(anyString())).thenReturn("<html>502 Bad Gateway</html>");

        String name = generate(new MethodNameGenerator(service));

        assertNotNull(name);
        assertNotEquals("validateOrderTotals", name);
        assertTrue(name.matches("[a-z][A-Za-z0-9]*"), name);
    }

    @Test
    void fallsBackWhenAiServiceThrows() throws IOException, InterruptedException {
        GeminiAIService service = mock(GeminiAIService.class);
        when(service.sendApiRequest(anyString())).thenThrow(new IOException("boom"));

        assertNotNull(generate(new MethodNameGenerator(service)));
    }

    private static String generate(MethodNameGenerator generator) {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Fixture {
                    void m() {
                        int total = 0;
                        total = total + 1;
                        System.out.println(total);
                    }
                }
                """);
        ClassOrInterfaceDeclaration clazz = cu.getClassByName("Fixture").get();
        MethodDeclaration m = clazz.getMethodsByName("m").get(0);
        StatementSequence seq = new StatementSequence(m.getBody().get().getStatements(), null, 0, m,
                ContainerType.METHOD, cu, null);
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq);
        when(cluster.duplicates()).thenReturn(List.of());
        return generator.generateName(cluster, RefactoringStrategy.EXTRACT_HELPER_METHOD, clazz,
                MethodNameGenerator.NamingStrategy.AI_POWERED, null);
    }
}
