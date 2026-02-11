package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts parameters and arguments from variation analysis.
 * Converts varying expressions into parameters and variable references into
 * arguments.
 */
public class ASTParameterExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ASTParameterExtractor.class);

    /**
     * Extract parameters and arguments from variation analysis.
     * 
     * @param analysis Variation analysis result
     * @return Extraction plan with parameters and arguments
     */
    public ExtractionPlan extractParameters(VariationAnalysis analysis) {
        return extractParameters(analysis, Set.of(), Set.of());
    }

    /**
     * Extract parameters and arguments from variation analysis with context info.
     * 
     * @param analysis          Variation analysis result
     * @param capturedVariables Variables captured from outer scope (for lambdas/anonymous classes)
     * @param outerFieldAccess  Fields accessed from outer class (for anonymous classes)
     * @return Extraction plan with parameters, arguments, and context info
     */
    public ExtractionPlan extractParameters(
            VariationAnalysis analysis,
            Set<String> capturedVariables,
            Set<String> outerFieldAccess) {
        List<ParameterSpec> parameters = new ArrayList<>();
        List<VariableReference> arguments = new ArrayList<>();

        for (VaryingExpression variation : analysis.varyingExpressions()) {
            String name = inferParameterName(variation.expr1());
            Type type = convertToJavaParserType(variation.type());

            // Skip captured variables - they are available via closure, not as parameters
            if (capturedVariables.contains(name)) {
                logger.debug("[ASTParameterExtractor] Skipping captured variable: {}", name);
                continue;
            }

            // Get example values
            List<String> examples = List.of(
                    variation.expr1().toString(),
                    variation.expr2().toString());

            // Get location if available
            Integer line = variation.expr1().getRange().map(r -> r.begin.line).orElse(null);
            Integer column = variation.expr1().getRange().map(r -> r.begin.column).orElse(null);

            parameters.add(new ParameterSpec(
                    name,
                    type,
                    examples,
                    variation.position(),
                    line,
                    column));
        }

        // Variable references can be used directly as arguments
        // Filter out captured variables and outer field access - they're available via closure
        for (VariableReference ref : analysis.variableReferences()) {
            if (!capturedVariables.contains(ref.name()) 
                    && !outerFieldAccess.contains(ref.name())) {
                arguments.add(ref);
            }
        }

        return new ExtractionPlan(parameters, arguments, capturedVariables, outerFieldAccess);
    }

    /**
     * Infer a parameter name from an expression.
     */
    private String inferParameterName(Expression expr) {
        // Try to infer from expression type
        if (expr.isStringLiteralExpr()) {
            return "str";
        } else if (expr.isIntegerLiteralExpr()) {
            return "num";
        } else if (expr.isLongLiteralExpr()) {
            return "longValue";
        } else if (expr.isDoubleLiteralExpr()) {
            return "doubleValue";
        } else if (expr.isBooleanLiteralExpr()) {
            return "flag";
        } else if (expr.isNameExpr()) {
            return expr.asNameExpr().getNameAsString();
        } else if (expr.isMethodCallExpr()) {
            return expr.asMethodCallExpr().getNameAsString() + "Result";
        } else if (expr.isFieldAccessExpr()) {
            return expr.asFieldAccessExpr().getNameAsString();
        }

        return "param";
    }

    /**
     * Convert a resolved type to JavaParser Type.
     */
    private Type convertToJavaParserType(ResolvedType resolvedType) {
        if (resolvedType == null) {
            return new ClassOrInterfaceType(null, "Object");
        }

        try {
            // Try to parse the type description
            String typeDesc = resolvedType.describe();
            return StaticJavaParser.parseType(typeDesc);
        } catch (Exception e) {
            logger.debug("[ASTParameterExtractor] Could not parse type: {}", resolvedType.describe());
            // Fallback to simple class name
            return new ClassOrInterfaceType(null, resolvedType.describe());
        }
    }
}
