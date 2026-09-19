package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Common base class for ParameterResolver and ReturnTypeResolver.
 * Consolidates type resolution and context lookup logic.
 */
public abstract class AbstractResolver {

    protected static final String OBJECT = "Object";
    protected final DataFlowAnalyzer dataFlowAnalyzer;

    protected AbstractResolver(DataFlowAnalyzer dataFlowAnalyzer) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
    }

    /**
     * Resolves a Type to an AST Type object, using symbol resolution if possible.
     */
    protected Type resolveTypeToAST(Type type, Node contextNode, StatementSequence sequence) {
        var classDecl = contextNode.findAncestor(ClassOrInterfaceDeclaration.class);
        if (classDecl.isPresent()) {
            String fqn = AbstractCompiler.resolveTypeFqn(type, classDecl.get(), null);
            if (fqn != null && !fqn.equals("java.lang.Object") && !fqn.equals(OBJECT)) {
                return StaticJavaParser.parseType(simplifyType(fqn));
            }
        }

        String astType = type.asString();
        if ("var".equals(astType)) {
            if (contextNode instanceof VariableDeclarator v && v.getInitializer().isPresent()) {
                var init = v.getInitializer().get();
                return resolveExpressionTypeToAST(init, sequence);
            }
            return StaticJavaParser.parseType(OBJECT);
        }
        return type;
    }

    /**
     * Resolves an expression's type to a JavaParser Type object.
     */
    protected Type resolveExpressionTypeToAST(Expression expr, StatementSequence sequence) {
        try {
            ResolvedType resolved = expr.calculateResolvedType();
            return convertResolvedTypeToJavaParserType(resolved);
        } catch (Exception e) {
            if (expr.isMethodCallExpr()) {
                Type type = inferTypeFromMethodCall(expr.asMethodCallExpr(), sequence);
                if (type != null) return type;
            }
            return inferTypeFromExpression(expr);
        }
    }

    /**
     * Find the type of a variable in the given context.
     */
    protected Type findTypeInContext(StatementSequence sequence, String varName) {
        // 1. Scan statements for variable declarations
        for (Statement stmt : sequence.statements()) {
            Optional<Type> type = findVarTypeInStatement(stmt, varName, sequence);
            if (type.isPresent()) {
                return type.get();
            }
        }

        // 2. Check field declarations in the containing class
        Optional<CallableDeclaration<?>> callableOpt = sequence.getContainingCallable();
        if (callableOpt.isPresent()) {
            CallableDeclaration<?> callable = callableOpt.get();
            var classDecl = callable.findAncestor(ClassOrInterfaceDeclaration.class);
            if (classDecl.isPresent()) {
                for (var field : classDecl.get().getFields()) {
                    for (var v : field.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            return resolveTypeToAST(field.getElementType(), field, sequence);
                        }
                    }
                }
            }
            for (var param : callable.getParameters()) {
                if (param.getNameAsString().equals(varName)) {
                    return resolveTypeToAST(param.getType(), param, sequence);
                }
            }

            // 3. Scan method body for variables declared outside the block
            if (sequence.getCallableBody().isPresent()) {
                Optional<Type> type = findVarTypeInStatement(sequence.getCallableBody().get(), varName, sequence);
                if (type.isPresent()) {
                    return type.get();
                }
            }
        }

        return StaticJavaParser.parseType(OBJECT);
    }

    protected Optional<Type> findVarTypeInStatement(Node node, String varName, StatementSequence sequence) {
        class VarTypeVisitor extends com.github.javaparser.ast.visitor.GenericVisitorAdapter<Type, String> {
            @Override
            public Type visit(VariableDeclarator n, String name) {
                if (n.getNameAsString().equals(name)) {
                    return resolveTypeToAST(n.getType(), n, sequence);
                }
                return super.visit(n, name);
            }

            @Override
            public Type visit(com.github.javaparser.ast.expr.LambdaExpr n, String name) {
                for (com.github.javaparser.ast.body.Parameter param : n.getParameters()) {
                    if (param.getNameAsString().equals(name)) {
                        if (!param.getType().isUnknownType() && !param.getType().isVarType()) {
                            return resolveTypeToAST(param.getType(), n, sequence);
                        }
                        return StaticJavaParser.parseType(OBJECT);
                    }
                }
                return super.visit(n, name);
            }
        }
        return Optional.ofNullable(node.accept(new VarTypeVisitor(), varName));
    }

    protected Type inferTypeFromMethodCall(MethodCallExpr methodCall, StatementSequence sequence) {
        try {
            ResolvedType resolved = methodCall.calculateResolvedType();
            return convertResolvedTypeToJavaParserType(resolved);
        } catch (Exception e) {
            return inferTypeFromMethodCallManually(methodCall, sequence);
        }
    }

    /**
     * Source-level fallback when symbol resolution fails. Resolves the scope of the call
     * (which may itself be a chained call, {@code this}, a field or a constructor call),
     * locates the declaring class among the parsed compilation units and picks the
     * overload with a matching arity. Class type parameters in the declared return type
     * are substituted with the type arguments of the scope, e.g.
     * {@code Box<String> b; b.get()} yields {@code String}.
     */
    private Type inferTypeFromMethodCallManually(MethodCallExpr methodCall, StatementSequence sequence) {
        String methodName = methodCall.getNameAsString();
        int arity = methodCall.getArguments().size();

        if (methodCall.getScope().isEmpty()) {
            return sequence.getContainingCallable()
                    .flatMap(c -> c.findAncestor(ClassOrInterfaceDeclaration.class))
                    .flatMap(decl -> selectOverload(decl.getMethodsByName(methodName), arity))
                    .map(m -> resolveTypeToAST(m.getType(), m, sequence))
                    .orElse(null);
        }

        Type scopeType = resolveScopeType(methodCall.getScope().get(), sequence);
        if (scopeType == null || OBJECT.equals(scopeType.asString())) {
            return null;
        }

        String erasedScope = erase(scopeType.asString());
        CompilationUnit typeCU = findCompilationUnitByTypeName(erasedScope);
        if (typeCU == null) {
            return null;
        }

        Optional<ClassOrInterfaceDeclaration> declaring = typeCU.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(simplifyType(erasedScope)))
                .findFirst();
        List<MethodDeclaration> byName = declaring
                .map(c -> c.getMethodsByName(methodName))
                .orElseGet(() -> typeCU.findAll(MethodDeclaration.class).stream()
                        .filter(m -> m.getNameAsString().equals(methodName)).toList());

        Optional<MethodDeclaration> method = selectOverload(byName, arity);
        if (method.isEmpty()) {
            return null;
        }

        Type declared = method.get().getType();
        if (declaring.isPresent() && scopeType.isClassOrInterfaceType()) {
            Type substituted = substituteClassTypeParameters(declared, declaring.get(),
                    scopeType.asClassOrInterfaceType());
            if (substituted != null) {
                return substituted;
            }
        }
        return resolveTypeToAST(declared, method.get(), sequence);
    }

    private static Optional<MethodDeclaration> selectOverload(List<MethodDeclaration> candidates, int arity) {
        return candidates.stream()
                .filter(m -> m.getParameters().size() == arity
                        || (m.getParameters().isNonEmpty()
                            && m.getParameters().getLast().get().isVarArgs()
                            && arity >= m.getParameters().size() - 1))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    private Type resolveScopeType(Expression scope, StatementSequence sequence) {
        if (scope.isEnclosedExpr()) {
            return resolveScopeType(scope.asEnclosedExpr().getInner(), sequence);
        }
        if (scope.isNameExpr()) {
            Type inContext = findTypeInContext(sequence, scope.asNameExpr().getNameAsString());
            if (!OBJECT.equals(inContext.asString())) {
                return inContext;
            }
            // May be a static reference to a class (e.g. Collections.emptyList())
            return StaticJavaParser.parseType(scope.asNameExpr().getNameAsString());
        }
        if (scope.isThisExpr()) {
            return sequence.getContainingCallable()
                    .flatMap(c -> c.findAncestor(ClassOrInterfaceDeclaration.class))
                    .map(c -> (Type) StaticJavaParser.parseType(c.getNameAsString()))
                    .orElse(null);
        }
        if (scope.isFieldAccessExpr()) {
            return findTypeInContext(sequence, scope.asFieldAccessExpr().getNameAsString());
        }
        if (scope.isMethodCallExpr()) {
            return inferTypeFromMethodCall(scope.asMethodCallExpr(), sequence);
        }
        if (scope.isObjectCreationExpr()) {
            return inferTypeFromExpression(scope);
        }
        if (scope.isCastExpr()) {
            return scope.asCastExpr().getType();
        }
        return resolveExpressionTypeToAST(scope, sequence);
    }

    private Type substituteClassTypeParameters(Type declared, ClassOrInterfaceDeclaration declaring,
            ClassOrInterfaceType scopeType) {
        var typeParams = declaring.getTypeParameters();
        if (typeParams.isEmpty() || scopeType.getTypeArguments().isEmpty()
                || scopeType.getTypeArguments().get().size() != typeParams.size()) {
            return null;
        }
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < typeParams.size(); i++) {
            bindings.put(typeParams.get(i).getNameAsString(), scopeType.getTypeArguments().get().get(i));
        }
        if (!mentionsAny(declared, bindings.keySet())) {
            return null;
        }
        Type result = declared.clone();
        if (result.isClassOrInterfaceType() && bindings.containsKey(result.asString())) {
            return bindings.get(result.asString()).clone();
        }
        result.walk(ClassOrInterfaceType.class, t -> {
            Type bound = bindings.get(t.getNameAsString());
            if (bound != null && t.getScope().isEmpty() && t.getTypeArguments().isEmpty()
                    && t.getParentNode().isPresent()) {
                t.replace(bound.clone());
            }
        });
        return result;
    }

    private static boolean mentionsAny(Type type, Set<String> names) {
        return type.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
                .anyMatch(n -> names.contains(n.getIdentifier()))
                || names.contains(type.asString());
    }

    private static String erase(String type) {
        int lt = type.indexOf('<');
        return lt > 0 ? type.substring(0, lt) : type;
    }

    protected Type inferTypeFromExpression(Expression expr) {
        if (expr.isEnclosedExpr()) return inferTypeFromExpression(expr.asEnclosedExpr().getInner());
        if (expr.isStringLiteralExpr()) return StaticJavaParser.parseType("String");
        if (expr.isIntegerLiteralExpr()) return StaticJavaParser.parseType("int");
        if (expr.isLongLiteralExpr()) return StaticJavaParser.parseType("long");
        if (expr.isDoubleLiteralExpr()) {
            String value = expr.asDoubleLiteralExpr().getValue().toLowerCase();
            return StaticJavaParser.parseType(value.endsWith("f") ? "float" : "double");
        }
        if (expr.isBooleanLiteralExpr()) return StaticJavaParser.parseType("boolean");
        if (expr.isCharLiteralExpr()) return StaticJavaParser.parseType("char");
        if (expr.isCastExpr()) return expr.asCastExpr().getType().clone();
        if (expr.isObjectCreationExpr()) return inferTypeFromObjectCreation(expr.asObjectCreationExpr());
        if (expr.isArrayCreationExpr()) return expr.asArrayCreationExpr().createdType().clone();
        if (expr.isConditionalExpr()) return inferTypeFromExpression(expr.asConditionalExpr().getThenExpr());
        if (expr.isUnaryExpr()) {
            var unary = expr.asUnaryExpr();
            if (unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return StaticJavaParser.parseType("boolean");
            }
            return inferTypeFromExpression(unary.getExpression());
        }
        if (expr.isBinaryExpr()) return inferTypeFromBinary(expr.asBinaryExpr());
        if (expr.isInstanceOfExpr()) return StaticJavaParser.parseType("boolean");
        return StaticJavaParser.parseType(OBJECT);
    }

    /**
     * {@code new ArrayList<>()} carries no usable type arguments of its own; take them from
     * the declaration or assignment it initialises, otherwise fall back to the raw type.
     */
    private Type inferTypeFromObjectCreation(ObjectCreationExpr creation) {
        ClassOrInterfaceType created = creation.getType();
        boolean diamond = created.getTypeArguments().map(NodeList::isEmpty).orElse(false);
        if (!diamond) {
            return created.clone();
        }
        Optional<Node> parent = creation.getParentNode();
        if (parent.isPresent() && parent.get() instanceof VariableDeclarator v
                && !v.getType().isVarType()) {
            return v.getType().clone();
        }
        if (parent.isPresent() && parent.get() instanceof AssignExpr assign
                && assign.getTarget().isNameExpr()) {
            Optional<VariableDeclarator> decl = assign.findAncestor(CallableDeclaration.class)
                    .flatMap(c -> c.findFirst(VariableDeclarator.class,
                            d -> d.getNameAsString().equals(assign.getTarget().asNameExpr().getNameAsString())));
            if (decl.isPresent()) {
                return decl.get().getType().clone();
            }
        }
        ClassOrInterfaceType raw = created.clone();
        raw.removeTypeArguments();
        return raw;
    }

    private Type inferTypeFromBinary(BinaryExpr binary) {
        switch (binary.getOperator()) {
            case EQUALS, NOT_EQUALS, LESS, GREATER, LESS_EQUALS, GREATER_EQUALS, AND, OR -> {
                return StaticJavaParser.parseType("boolean");
            }
            default -> {
                Type left = inferTypeFromExpression(binary.getLeft());
                Type right = inferTypeFromExpression(binary.getRight());
                if (binary.getOperator() == BinaryExpr.Operator.PLUS
                        && ("String".equals(left.asString()) || "String".equals(right.asString()))) {
                    return StaticJavaParser.parseType("String");
                }
                return StaticJavaParser.parseType(promote(left.asString(), right.asString()));
            }
        }
    }

    private static final List<String> NUMERIC_RANK = List.of("double", "float", "long", "int");

    private static String promote(String a, String b) {
        for (String t : NUMERIC_RANK) {
            if (t.equals(a) || t.equals(b)) {
                return t;
            }
        }
        return "int";
    }

    protected CompilationUnit findCompilationUnit(StatementSequence sequence, String scopeName) {
        Type scopeType = findTypeInContext(sequence, scopeName);
        return findCompilationUnitByTypeName(scopeType.asString());
    }

    protected CompilationUnit findCompilationUnitByTypeName(String typeStr) {
        Map<String, CompilationUnit> allCUs = AntikytheraRunTime.getResolvedCompilationUnits();
        CompilationUnit typeCU = allCUs.get(typeStr);
        if (typeCU == null) {
            for (var entry : allCUs.entrySet()) {
                if (entry.getKey().endsWith("." + typeStr) || entry.getKey().equals(typeStr)) {
                    typeCU = entry.getValue();
                    break;
                }
            }
        }
        return typeCU;
    }

    /**
     * Converts a resolved type into an AST type that can be written into a signature.
     * Package qualifiers are stripped throughout (including inside type arguments and
     * wildcard bounds); unresolved type variables become {@code Object} at the top level
     * and cause the type arguments to be dropped (raw type) when nested, since a foreign
     * {@code T} would not compile in the helper method.
     */
    protected Type convertResolvedTypeToJavaParserType(ResolvedType resolvedType) {
        if (resolvedType == null || resolvedType.isTypeVariable()) {
            return new ClassOrInterfaceType(null, OBJECT);
        }
        if (resolvedType.isPrimitive() || resolvedType.isVoid()) {
            return StaticJavaParser.parseType(resolvedType.describe());
        }
        if (resolvedType.isArray()) {
            Type component = convertResolvedTypeToJavaParserType(resolvedType.asArrayType().getComponentType());
            return new ArrayType(component);
        }
        if (resolvedType instanceof ResolvedReferenceType ref) {
            return convertReferenceType(ref);
        }

        try {
            return simplifyQualifiers(StaticJavaParser.parseType(resolvedType.describe()));
        } catch (Exception e) {
            return new ClassOrInterfaceType(null, simplifyType(erase(resolvedType.describe())));
        }
    }

    private Type convertReferenceType(ResolvedReferenceType ref) {
        ClassOrInterfaceType type = new ClassOrInterfaceType(null, simplifyType(ref.getQualifiedName()));
        List<ResolvedType> args = ref.typeParametersValues();
        if (args.isEmpty()) {
            return type;
        }
        NodeList<Type> converted = new NodeList<>();
        for (ResolvedType arg : args) {
            Type argType = convertTypeArgument(arg);
            if (argType == null) {
                return type; // raw type
            }
            converted.add(argType);
        }
        type.setTypeArguments(converted);
        return type;
    }

    private Type convertTypeArgument(ResolvedType arg) {
        if (arg.isTypeVariable()) {
            return null;
        }
        if (arg.isWildcard()) {
            var wildcard = arg.asWildcard();
            if (!wildcard.isBounded()) {
                return new WildcardType();
            }
            Type bound = convertTypeArgument(wildcard.getBoundedType());
            if (bound == null) {
                return new WildcardType();
            }
            if (!(bound instanceof ReferenceType refBound)) {
                return null;
            }
            return wildcard.isExtends() ? new WildcardType(refBound) : new WildcardType(null, refBound, new NodeList<>());
        }
        return convertResolvedTypeToJavaParserType(arg);
    }

    private Type simplifyQualifiers(Type type) {
        type.walk(ClassOrInterfaceType.class, t -> {
            if (t.getScope().isPresent() && t.getParentNode().isPresent()) {
                t.removeScope();
            }
        });
        if (type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getScope().isPresent()) {
            type.asClassOrInterfaceType().removeScope();
        }
        return type;
    }

    protected String simplifyType(String fqn) {
        if (fqn == null) return null;
        if (fqn.equals("int") || fqn.equals("boolean") || fqn.equals("double") || fqn.equals("void") || fqn.equals("long")) return fqn;

        int lastDot = fqn.lastIndexOf('.');
        if (lastDot > 0) {
            return fqn.substring(lastDot + 1);
        }
        return fqn;
    }
}
