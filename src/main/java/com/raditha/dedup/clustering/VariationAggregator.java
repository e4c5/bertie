package com.raditha.dedup.clustering;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analysis.ASTVariationAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ExprInfo;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariableReference;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VaryingExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates and deduplicates variations across all duplicates in a cluster.
 * This extracts logic from the main generateRecommendation method to improve clarity.
 */
public class VariationAggregator {

    private final ASTVariationAnalyzer astAnalyzer;

    public VariationAggregator() {
        this.astAnalyzer = new ASTVariationAnalyzer();
    }

    public VariationAggregator(ASTVariationAnalyzer astAnalyzer) {
        this.astAnalyzer = astAnalyzer;
    }

    /**
     * Aggregate variations from comparing the primary sequence against all duplicates.
     * 
     * @param cluster The duplicate cluster to analyze
     * @return Aggregated variations including unique variations, variable references, 
     *         internal variables, and expression bindings
     */
    public AggregatedVariations aggregate(DuplicateCluster cluster) {
        StatementSequence primary = cluster.primary();
        CompilationUnit cu = primary.compilationUnit();

        List<VaryingExpression> allVariations = new ArrayList<>();
        Set<VariableReference> allVarRefs = new HashSet<>();
        Set<String> allInternalVars = new HashSet<>();

        if (cluster.duplicates().isEmpty()) {
            // Edge case: compare primary against itself
            VariationAnalysis analysis = astAnalyzer.analyzeVariations(primary, primary, cu);
            allVariations.addAll(analysis.varyingExpressions());
            allVarRefs.addAll(analysis.variableReferences());
            allInternalVars.addAll(analysis.getDeclaredInternalVariables());
        } else {
            // Compare primary against each duplicate
            for (SimilarityPair pair : cluster.duplicates()) {
                StatementSequence duplicate = pair.seq2();
                VariationAnalysis analysis = astAnalyzer.analyzeVariations(primary, duplicate, cu);

                allVariations.addAll(analysis.varyingExpressions());
                allVarRefs.addAll(analysis.variableReferences());
                allInternalVars.addAll(analysis.getDeclaredInternalVariables());
            }
        }

        // Deduplicate variations based on position
        Map<Integer, VaryingExpression> uniqueVariations = new HashMap<>();
        for (VaryingExpression v : allVariations) {
            uniqueVariations.putIfAbsent(v.position(), v);
        }

        // Build expression bindings
        Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings = new HashMap<>();

        // Populate bindings for primary using unique variations
        for (VaryingExpression v : uniqueVariations.values()) {
            exprBindings.computeIfAbsent(v.position(), k -> new HashMap<>())
                    .put(primary, ExprInfo.fromExpression(v.expr1()));
        }

        // Populate bindings for duplicates by re-analyzing
        if (!cluster.duplicates().isEmpty()) {
            for (SimilarityPair pair : cluster.duplicates()) {
                StatementSequence duplicate = pair.seq2();
                VariationAnalysis pairAnalysis = astAnalyzer.analyzeVariations(primary, duplicate, cu);

                for (VaryingExpression v : pairAnalysis.varyingExpressions()) {
                    int pos = v.position();
                    if (uniqueVariations.containsKey(pos)) {
                        exprBindings.computeIfAbsent(pos, k -> new HashMap<>())
                                .put(duplicate, ExprInfo.fromExpression(v.expr2()));
                    }
                }
            }
        }

        return new AggregatedVariations(
                uniqueVariations,
                allVarRefs,
                allInternalVars,
                exprBindings
        );
    }

    /**
     * Build a VariationAnalysis from aggregated results.
     * This provides backward compatibility with existing code.
     */
    public VariationAnalysis buildAnalysis(AggregatedVariations aggregated) {
        return VariationAnalysis.builder()
                .varyingExpressions(new ArrayList<>(aggregated.uniqueVariations().values()))
                .variableReferences(aggregated.variableReferences())
                .declaredInternalVariables(aggregated.declaredInternalVariables())
                .exprBindings(aggregated.exprBindings())
                .build();
    }

    /**
     * Detect fields that are common across all classes in a cluster.
     * This is used for parent class extraction to identify fields that should be moved to the parent.
     * 
     * @param cluster The duplicate cluster to analyze
     * @return List of field declarations that are common to all classes
     */
    public List<com.github.javaparser.ast.body.FieldDeclaration> detectCommonFields(DuplicateCluster cluster) {
        // Get all unique classes involved in the cluster
        Set<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classes = new HashSet<>();
        
        for (StatementSequence seq : cluster.allSequences()) {
            seq.containingMethod()
                    .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .ifPresent(classes::add);
        }
        
        if (classes.size() < 2) {
            // Need at least 2 classes for field duplication
            return new ArrayList<>();
        }
        
        // Build field signature map for each class
        Map<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration, Map<String, com.github.javaparser.ast.body.FieldDeclaration>> classToFields = new HashMap<>();
        
        for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz : classes) {
            Map<String, com.github.javaparser.ast.body.FieldDeclaration> fieldMap = new HashMap<>();
            
            for (com.github.javaparser.ast.body.FieldDeclaration field : clazz.getFields()) {
                // Skip static fields
                if (field.isStatic()) {
                    continue;
                }
                
                // Create signature: type + name for each variable
                for (com.github.javaparser.ast.body.VariableDeclarator var : field.getVariables()) {
                    String signature = field.getCommonType().asString() + ":" + var.getNameAsString();
                    fieldMap.put(signature, field);
                }
            }
            
            classToFields.put(clazz, fieldMap);
        }
        
        // Find fields common to ALL classes
        List<com.github.javaparser.ast.body.FieldDeclaration> commonFields = new ArrayList<>();
        Set<String> processedSignatures = new HashSet<>();
        
        // Start with fields from first class
        var firstClass = classes.iterator().next();
        Map<String, com.github.javaparser.ast.body.FieldDeclaration> firstClassFields = classToFields.get(firstClass);
        
        for (Map.Entry<String, com.github.javaparser.ast.body.FieldDeclaration> entry : firstClassFields.entrySet()) {
            String signature = entry.getKey();
            
            // Check if this field exists in ALL other classes
            boolean existsInAll = true;
            for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz : classes) {
                if (clazz == firstClass) {
                    continue;
                }
                
                if (!classToFields.get(clazz).containsKey(signature)) {
                    existsInAll = false;
                    break;
                }
            }
            
            if (existsInAll && !processedSignatures.contains(signature)) {
                commonFields.add(entry.getValue());
                processedSignatures.add(signature);
            }
        }
        
        return commonFields;
    }
}
