package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes variations between duplicate code sequences using AST comparison.
 * Identifies varying expressions and variable references for parameter
 * extraction.
 */
public class ASTVariationAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ASTVariationAnalyzer.class);

    /**
     * Analyze variations between two statement sequences.
     * 
     * @param seq1 First sequence
     * @param seq2 Second sequence
     * @param cu1  CompilationUnit containing first sequence
     * @return Analysis with varying expressions and variable references
     */
    public VariationAnalysis analyzeVariations(
            StatementSequence seq1,
            StatementSequence seq2,
            CompilationUnit cu1) {
        List<VaryingExpression> variations = new ArrayList<>();
        Set<VariableReference> varRefs = new HashSet<>();

        // Walk both sequences in parallel
        int minSize = Math.min(seq1.statements().size(), seq2.statements().size());

        // Collect variables declared within the sequence (to avoid treating them as
        // external references)
        Set<String> declaredInternalVars = new HashSet<>();
        for (Statement stmt : seq1.statements()) {
            findDeclarations(stmt, declaredInternalVars);
        }

        for (int i = 0; i < minSize; i++) {
            Statement stmt1 = seq1.statements().get(i);
            Statement stmt2 = seq2.statements().get(i);

            // Find differing expressions
            findDifferences(stmt1, stmt2, i, variations);

            // Find variable references in first sequence (representative)
            findVariableReferences(stmt1, varRefs, declaredInternalVars);
        }


        // Create VariationAnalysis with AST-based data
        // Filter out parent variations (e.g. if 'assertEquals("a", b)' varies because
        // '"a"' varies,
        // we only want to parameterize '"a"', not the whole call)
        List<VaryingExpression> filteredVariations = filterParentVariations(variations);

        // CRITICAL: Sort by position to ensure parameters are in correct order
        // filterParentVariations() can reorder the list, so we must sort here
        filteredVariations.sort(Comparator.comparingInt(VaryingExpression::position));

        logger.debug("[ASTVariationAnalyzer] After filtering and sorting: {} varying expressions",
                filteredVariations.size());

        return VariationAnalysis.builder()
                .varyingExpressions(filteredVariations)
                .variableReferences(varRefs)
                .declaredInternalVariables(declaredInternalVars)
                .build();
    }

    /**
     * Filter out variations that are ancestors of other variations.
     * We prefer the most specific variation.
     */
    private List<VaryingExpression> filterParentVariations(List<VaryingExpression> variations) {
        List<VaryingExpression> result = new ArrayList<>();
        for (VaryingExpression v1 : variations) {
            boolean isParent = false;
            for (VaryingExpression v2 : variations) {
                if (v1 != v2 && v1.expr1().isAncestorOf(v2.expr1())){
                    isParent = true;
                    break;
                }
            }
            if (!isParent) {
                result.add(v1);
            }
        }
        return result;
    }

    /**
     * Find variable declarations in a statement.
     */
    private void findDeclarations(Statement stmt, Set<String> declaredVars) {
        stmt.accept(new com.github.javaparser.ast.visitor.VoidVisitorAdapter<Set<String>>() {
            @Override
            public void visit(com.github.javaparser.ast.body.VariableDeclarator n, Set<String> arg) {
                super.visit(n, arg);
                arg.add(n.getNameAsString());
            }

            @Override
            public void visit(com.github.javaparser.ast.expr.LambdaExpr n, Set<String> arg) {
                super.visit(n, arg);
                n.getParameters().forEach(p -> arg.add(p.getNameAsString()));
            }
        }, declaredVars);
    }

    /**
     * Find expressions that differ between two statements.
     */
    private void findDifferences(
            Statement stmt1,
            Statement stmt2,
            int position,
            List<VaryingExpression> variations) {
        // Get all expressions from both statements, excluding EnclosedExpr (parentheses)
        // to ensure alignment between "(x+1)" and "x+1"
        List<Expression> exprs1 = stmt1.findAll(Expression.class, e -> !e.isEnclosedExpr());
        List<Expression> exprs2 = stmt2.findAll(Expression.class, e -> !e.isEnclosedExpr());

        // Compare expressions at same positions
        int minExprs = Math.min(exprs1.size(), exprs2.size());

        for (int i = 0; i < minExprs; i++) {
            Expression e1 = exprs1.get(i);
            Expression e2 = exprs2.get(i);

            if (!expressionsEquivalent(e1, e2)) {

                // Resolve type
                ResolvedType type1 = resolveExpressionType(e1);
                ResolvedType type2 = resolveExpressionType(e2);

                // CRITICAL FIX: Ensure type compatibility using Common Supertype Resolution
                ResolvedType commonType = null;
                if (type1 != null && type2 != null) {
                    commonType = findCommonSupertype(type1, type2);
                }

                // CRITICAL FIX: Ensure unique position for each expression in statement
                // Encode statement position in high bits, expression index in low bits
                int uniquePos = (position << 16) + i;
                variations.add(new VaryingExpression(uniquePos, e1, e2, commonType));
            }
        }
    }

    /**
     * Check if two expressions are semantically equivalent.
     * Uses AST comparison, ignoring parentheses and comments.
     */
    private boolean expressionsEquivalent(Expression e1, Expression e2) {
        // Create clones to avoid modifying original AST
        Expression u1 = e1.clone();
        Expression u2 = e2.clone();

        // Recursively remove parentheses and comments
        removeAllParentheses(u1);
        removeAllParentheses(u2);

        removeComments(u1);
        removeComments(u2);

        // Unwrap top-level parentheses if any remained (should be handled by removeAllParentheses but safe to ensure)
        u1 = unwrap(u1);
        u2 = unwrap(u2);

        // Use toString() which uses the pretty printer.
        // Since we removed comments and parentheses, this should be robust.
        return u1.toString().equals(u2.toString());
    }

    /**
     * Unwrap EnclosedExpr (parentheses) recursively.
     */
    private Expression unwrap(Expression expr) {
        if (expr.isEnclosedExpr()) {
            return unwrap(expr.asEnclosedExpr().getInner());
        }
        return expr;
    }

    /**
     * Recursively remove EnclosedExpr (parentheses) from a node and its children.
     * This modifies the node structure in place.
     */
    private void removeAllParentheses(com.github.javaparser.ast.Node node) {
        // Process children first
        // We use a safe list copy to iterate because we might modify the children
        List<com.github.javaparser.ast.Node> children = new ArrayList<>(node.getChildNodes());
        for (com.github.javaparser.ast.Node child : children) {
            removeAllParentheses(child);
        }

        // Check if current node is EnclosedExpr
        if (node instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed) {
            Expression inner = enclosed.getInner();

            // We need to replace 'enclosed' with 'inner' in the parent
            if (node.getParentNode().isPresent()) {
                com.github.javaparser.ast.Node parent = node.getParentNode().get();
                // Replace in parent
                parent.replace(enclosed, inner);
            }
        }
    }

    /**
     * Recursively remove comments from a node and its children.
     */
    private void removeComments(com.github.javaparser.ast.Node node) {
        node.removeComment();
        for (com.github.javaparser.ast.Node child : node.getChildNodes()) {
            removeComments(child);
        }
    }

    /**
     * Find all variable references in a statement.
     */
    private void findVariableReferences(
            Statement stmt,
            Set<VariableReference> varRefs,
            Set<String> declaredInternalVars) {
        // Find all NameExpr (variable references)
        stmt.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();

            // Skip if declared internally
            if (declaredInternalVars.contains(name)) {
                return;
            }

            try {
                // Try to resolve using JavaParser's built-in resolution
                ResolvedValueDeclaration resolved = nameExpr.resolve();

                Scope scope = determineScope(resolved);
                ResolvedType type = resolved.getType();

                varRefs.add(new VariableReference(
                        name,
                        type,
                        scope));

                logger.debug("[ASTVariationAnalyzer] Variable reference: {} (scope: {})",
                        name, scope);

            } catch (Exception e) {
                // Heuristic: If name starts with Uppercase and resolution failed, assume it's a
                // Class reference (e.g. System)
                if (Character.isUpperCase(name.charAt(0))) {
                    logger.debug("[ASTVariationAnalyzer] Ignoring likely class reference: {}", name);
                    return;
                }

                // Fallback: manual AST lookup for fields
                ResolvedType fallback = manualFieldLookup(nameExpr, name);
                if (fallback != null) {
                    varRefs.add(new VariableReference(name, fallback, Scope.FIELD)); // Assume FIELD scope if found in
                                                                                     // class
                    logger.debug("[ASTVariationAnalyzer] Variable reference (fallback): {} (scope: FIELD)", name);
                } else {
                    // If resolution fails, add as UNKNOWN
                    varRefs.add(VariableReference.unknown(name));
                    logger.debug("[ASTVariationAnalyzer] Could not resolve variable: {}", name);
                }
            }
        });
    }

    /**
     * Determine the scope of a resolved variable.
     */
    private Scope determineScope(ResolvedValueDeclaration resolved) {
        if (resolved.isParameter()) {
            return Scope.PARAMETER;
        } else if (resolved.isVariable()) {
            return Scope.LOCAL_VAR;
        } else if (resolved.isField()) {
            return Scope.FIELD;
        }
        return Scope.UNKNOWN;
    }

    /**
     * Resolve the type of an expression.
     */
    private ResolvedType resolveExpressionType(Expression expr) {
        try {
            return expr.calculateResolvedType();
        } catch (Exception e) {
            // Fallback: manual AST lookup for fields
            if (expr.isNameExpr()) {
                String name = expr.asNameExpr().getNameAsString();
                ResolvedType fallback = manualFieldLookup(expr, name);
                if (fallback != null) {
                    return fallback;
                }
            }
            return null;
        }
    }

    private ResolvedType manualFieldLookup(com.github.javaparser.ast.Node node, String name) {
        Optional<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classDecl = node
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (classDecl.isPresent()) {
            Optional<com.github.javaparser.ast.body.FieldDeclaration> field = classDecl.get().getFieldByName(name);
            if (field.isPresent()) {
                String typeName = field.get().getCommonType().asString();
                return new SimpleResolvedType(typeName);
            }
        }
        return null;
    }

    /**
     * Find the most specific common supertype of two types.
     */
    private ResolvedType findCommonSupertype(ResolvedType t1, ResolvedType t2) {
        if (t1 == null || t2 == null) return null;
        if (t1.equals(t2)) return t1;

        // Handle SimpleResolvedType or exact description match
        if (t1.describe().equals(t2.describe())) return t1;

        // Check subtype relationships
        if (t1.isAssignableBy(t2)) return t1;
        if (t2.isAssignableBy(t1)) return t2;

        // Handle Reference Types
        if (t1 instanceof ResolvedReferenceType && t2 instanceof ResolvedReferenceType) {
            return findLCA((ResolvedReferenceType) t1, (ResolvedReferenceType) t2);
        }

        return null; // Fallback to Object (implied by null)
    }

    private ResolvedType findLCA(ResolvedReferenceType r1, ResolvedReferenceType r2) {
        Set<String> ancestors1 = new HashSet<>();
        ancestors1.add(r1.getQualifiedName());
        try {
            r1.getAllAncestors().forEach(a -> ancestors1.add(a.getQualifiedName()));
        } catch (Exception e) {
            // ignore resolution errors
        }

        List<ResolvedReferenceType> ancestors2 = new ArrayList<>();
        ancestors2.add(r2);
        try {
            ancestors2.addAll(r2.getAllAncestors());
        } catch (Exception e) {
            // ignore resolution errors
        }

        List<ResolvedReferenceType> common = new ArrayList<>();
        for (ResolvedReferenceType t : ancestors2) {
            // Must check name match AND assignability to handle generics correctly
            // e.g. List<String> vs List<Integer> -> List (raw) or Collection
            if (ancestors1.contains(t.getQualifiedName()) && t.isAssignableBy(r1)) {
                common.add(t);
            }
        }

        // Filter to find most specific
        List<ResolvedReferenceType> mostSpecific = new ArrayList<>(common);
        mostSpecific.removeIf(c -> {
             for (ResolvedReferenceType d : common) {
                 if (c != d && c.isAssignableBy(d)) return true; // d is subtype of c, so d is more specific
             }
             return false;
        });

        if (mostSpecific.isEmpty()) return null;

        // Prefer classes over interfaces if multiple
        return mostSpecific.stream()
            .filter(t -> !isInterface(t))
            .findFirst()
            .orElse(mostSpecific.get(0));
    }

    private boolean isInterface(ResolvedReferenceType t) {
        return t.getTypeDeclaration().map(ResolvedReferenceTypeDeclaration::isInterface).orElse(false);
    }

    /**
     * Minimal implementation of ResolvedType for fallback scenarios.
     */
    private static class SimpleResolvedType implements ResolvedType {
        private final String typeName;

        public SimpleResolvedType(String typeName) {
            this.typeName = typeName;
        }

        @Override
        public String describe() {
            return typeName;
        }

        @Override
        public boolean isArray() {
            return typeName.endsWith("[]");
        }

        @Override
        public boolean isPrimitive() {
            return false;
        } // Simplified

        @Override
        public boolean isReferenceType() {
            return true;
        } // simplified

        @Override
        public boolean isVoid() {
            return "void".equals(typeName);
        }

        @Override
        public boolean isAssignableBy(ResolvedType other) {
            // Minimal implementation: exact name match
            return other.describe().equals(this.describe());
        }
    }
}
