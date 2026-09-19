package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

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
        List<Expression> ordered = stmt1.findAll(Expression.class, e -> !e.isEnclosedExpr());
        alignNodes(stmt1, stmt2, position, ordered, variations);
    }

    private void alignNodes(
            Node n1,
            Node n2,
            int position,
            List<Expression> ordered,
            List<VaryingExpression> variations) {
        if (n1 instanceof Expression e1 && n2 instanceof Expression e2) {
            alignExpressions(unwrap(e1), unwrap(e2), position, ordered, variations);
            return;
        }

        if (n1.getClass() != n2.getClass()) {
            logger.debug("Structural divergence while aligning {} and {}", n1.getClass(), n2.getClass());
            return;
        }

        List<Node> children1 = nonCommentChildren(n1);
        List<Node> children2 = nonCommentChildren(n2);
        int childCount = Math.min(children1.size(), children2.size());
        if (children1.size() != children2.size()) {
            logger.debug("Child count mismatch while aligning {} and {}", n1.getClass(), n2.getClass());
        }
        for (int i = 0; i < childCount; i++) {
            Node child1 = children1.get(i);
            Node child2 = children2.get(i);
            if (child1.getClass() != child2.getClass()
                    && !(child1 instanceof Expression && child2 instanceof Expression)) {
                break;
            }
            alignNodes(child1, child2, position, ordered, variations);
        }
    }

    private void alignExpressions(
            Expression e1,
            Expression e2,
            int position,
            List<Expression> ordered,
            List<VaryingExpression> variations) {
        if (expressionsEquivalent(e1, e2)) {
            return;
        }

        if (sameShape(e1, e2)) {
            int before = variations.size();
            List<Node> children1 = nonCommentChildren(e1);
            List<Node> children2 = nonCommentChildren(e2);
            for (int i = 0; i < children1.size(); i++) {
                Node child1 = children1.get(i);
                Node child2 = children2.get(i);
                if (child1 instanceof Expression expression1 && child2 instanceof Expression expression2) {
                    alignExpressions(unwrap(expression1), unwrap(expression2), position, ordered, variations);
                } else if (child1.getClass() == child2.getClass()) {
                    alignNodes(child1, child2, position, ordered, variations);
                }
            }
            if (variations.size() == before) {
                recordVariation(e1, e2, position, ordered, variations);
            }
            return;
        }

        recordVariation(e1, e2, position, ordered, variations);
    }

    private boolean sameShape(Expression e1, Expression e2) {
        e1 = unwrap(e1);
        e2 = unwrap(e2);
        if (e1.getClass() != e2.getClass()) {
            return false;
        }

        List<Node> children1 = nonCommentChildren(e1);
        List<Node> children2 = nonCommentChildren(e2);
        if (children1.size() != children2.size()) {
            return false;
        }
        for (int i = 0; i < children1.size(); i++) {
            if (!sameShapeNode(children1.get(i), children2.get(i))) {
                return false;
            }
        }

        if (e1 instanceof BinaryExpr binary1 && e2 instanceof BinaryExpr binary2
                && binary1.getOperator() != binary2.getOperator()) {
            return false;
        }
        if (e1 instanceof UnaryExpr unary1 && e2 instanceof UnaryExpr unary2
                && unary1.getOperator() != unary2.getOperator()) {
            return false;
        }
        if (e1 instanceof AssignExpr assign1 && e2 instanceof AssignExpr assign2
                && assign1.getOperator() != assign2.getOperator()) {
            return false;
        }
        return true;
    }

    private boolean sameShapeNode(Node n1, Node n2) {
        if (n1 instanceof Expression && n2 instanceof Expression) {
            return true;
        }
        if (n1.getClass() != n2.getClass()) {
            return false;
        }

        List<Node> children1 = nonCommentChildren(n1);
        List<Node> children2 = nonCommentChildren(n2);
        if (children1.size() != children2.size()) {
            return false;
        }
        if (children1.isEmpty()) {
            return n1.equals(n2);
        }
        for (int i = 0; i < children1.size(); i++) {
            if (!sameShapeNode(children1.get(i), children2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<Node> nonCommentChildren(Node node) {
        return node.getChildNodes().stream()
                .filter(child -> !(child instanceof Comment))
                .toList();
    }

    private void recordVariation(
            Expression e1,
            Expression e2,
            int position,
            List<Expression> ordered,
            List<VaryingExpression> variations) {
        ResolvedType type1 = resolveExpressionType(e1);
        ResolvedType type2 = resolveExpressionType(e2);

        ResolvedType commonType = null;
        if (type1 != null && type2 != null) {
            commonType = findCommonSupertype(type1, type2);
        }

        int idx = indexOfIdentity(ordered, e1);
        if (idx < 0) {
            idx = ordered.size() + variations.size();
            logger.debug("Could not find expression identity in ordered sequence: {}", e1);
        }
        int uniquePos = (position << 16) + idx;
        variations.add(new VaryingExpression(uniquePos, e1, e2, commonType));
    }

    private int indexOfIdentity(List<Expression> expressions, Expression target) {
        for (int i = 0; i < expressions.size(); i++) {
            if (expressions.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if two expressions are semantically equivalent.
     * Uses AST comparison, ignoring parentheses and comments.
     */
    boolean expressionsEquivalent(Expression e1, Expression e2) {
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

        return u1.equals(u2);
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

            } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException
                    | IllegalArgumentException e) {
                logger.debug("[ASTVariationAnalyzer] Variable reference resolution failed for {}", name, e);
                CompilationUnit cu = nameExpr.findCompilationUnit().orElse(null);
                if (cu != null && lookupType(cu, name) != null) {
                    logger.debug("[ASTVariationAnalyzer] Resolved as type reference: {}", name);
                    return;
                }
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
                    logger.debug("[ASTVariationAnalyzer] Could not resolve variable: {}", name, e);
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
        } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException
                | IllegalArgumentException e) {
            logger.debug("[ASTVariationAnalyzer] Could not resolve expression type", e);
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
                CompilationUnit cu = node.findCompilationUnit().orElse(null);
                return new SimpleResolvedType(typeName, cu);
            }
        }
        return null;
    }

    private static TypeWrapper lookupType(CompilationUnit cu, String name) {
        try {
            return AbstractCompiler.findType(cu, name);
        } catch (RuntimeException e) {
            logger.debug("[ASTVariationAnalyzer] Type lookup failed for {}", name, e);
            return null;
        }
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
        if (isAssignable(t1, t2)) return t1;
        if (isAssignable(t2, t1)) return t2;

        // Handle Reference Types
        if (t1 instanceof ResolvedReferenceType && t2 instanceof ResolvedReferenceType) {
            try {
                return findLCA((ResolvedReferenceType) t1, (ResolvedReferenceType) t2);
            } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException
                    | IllegalArgumentException e) {
                // If resolution fails, fallback to Object
                logger.debug("Failed to resolve LCA for types {} and {}", t1.describe(), t2.describe(), e);
                return null;
            }
        }

        return null; // Fallback to Object (implied by null)
    }

    private ResolvedType findLCA(ResolvedReferenceType r1, ResolvedReferenceType r2) {
        Set<String> ancestors1 = new HashSet<>();
        ancestors1.add(r1.getQualifiedName());
        r1.getAllAncestors().forEach(a -> ancestors1.add(a.getQualifiedName()));

        List<ResolvedReferenceType> ancestors2 = new ArrayList<>();
        ancestors2.add(r2);
        ancestors2.addAll(r2.getAllAncestors());

        List<ResolvedReferenceType> common = new ArrayList<>();
        for (ResolvedReferenceType t : ancestors2) {
            if (!ancestors1.contains(t.getQualifiedName())) {
                continue;
            }
            // Same generic declaration with incompatible type arguments
            // (List<String> vs List<Integer>): fall back to the erasure, which both
            // sides can be assigned to, instead of walking further up to Object.
            if (isAssignable(t, r1)) {
                common.add(t);
            } else if (!t.getTypeParametersMap().isEmpty()) {
                ResolvedReferenceType erased = t.erasure().asReferenceType();
                if (isAssignable(erased, r1) || isAssignable(erased, r1.erasure())) {
                    common.add(erased);
                }
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

    private boolean isAssignable(ResolvedType target, ResolvedType source) {
        try {
            return target.isAssignableBy(source);
        } catch (UnsupportedOperationException e) {
            // NullType.isAssignableBy(Other) throws this
            logger.debug("Could not check assignability", e);
            return false;
        }
    }

    /**
     * Minimal implementation of ResolvedType for fallback scenarios.
     */
    static class SimpleResolvedType implements ResolvedType {
        private final String typeName;
        private final CompilationUnit context;

        /**
         * Creates a new SimpleResolvedType.
         *
         * @param typeName The type name
         */
        public SimpleResolvedType(String typeName) {
            this(typeName, null);
        }

        public SimpleResolvedType(String typeName, CompilationUnit context) {
            this.typeName = typeName.replaceFirst("<.*>", "");
            this.context = context;
        }

        /**
         * Returns the type description.
         */
        @Override
        public String describe() {
            return typeName;
        }

        /**
         * Checks if type is an array.
         */
        @Override
        public boolean isArray() {
            return typeName.endsWith("[]");
        }

        /**
         * Checks if type is a primitive.
         */
        @Override
        public boolean isPrimitive() {
            return false;
        } // Simplified

        /**
         * Checks if type is a reference type.
         */
        @Override
        public boolean isReferenceType() {
            return true;
        } // simplified

        /**
         * Checks if type is void.
         */
        @Override
        public boolean isVoid() {
            return "void".equals(typeName);
        }

        /**
         * Checks assignability.
         */
        @Override
        public boolean isAssignableBy(ResolvedType other) {
            if (other.describe().equals(typeName)) return true;
            if (other.isNull()) return true;

            if (context != null) {
                TypeWrapper self = lookupType(context, typeName);
                String otherName = other.isReferenceType() && !(other instanceof SimpleResolvedType)
                        ? other.asReferenceType().getQualifiedName()
                        : other.describe();
                TypeWrapper otherWrapper = lookupType(context, otherName);
                if (self != null && otherWrapper != null) {
                    return self.isAssignableFrom(otherWrapper);
                }
            }

            if (other.isReferenceType() && !(other instanceof SimpleResolvedType)) {
                ResolvedReferenceType ref = other.asReferenceType();
                if (matchesName(ref.getQualifiedName())) return true;
                try {
                    for (ResolvedReferenceType ancestor : ref.getAllAncestors()) {
                        if (matchesName(ancestor.getQualifiedName())) return true;
                    }
                } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException e) {
                    logger.debug("[ASTVariationAnalyzer] Failed to inspect type ancestors", e);
                }
            }
            return false;
        }

        private boolean matchesName(String qualifiedName) {
            return qualifiedName.equals(typeName) || qualifiedName.endsWith("." + typeName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SimpleResolvedType other)) return false;
            return typeName.equals(other.typeName);
        }

        @Override
        public int hashCode() {
            return typeName.hashCode();
        }
    }
}
