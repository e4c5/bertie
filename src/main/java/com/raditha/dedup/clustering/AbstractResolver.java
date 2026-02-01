package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.Map;
import java.util.Optional;

/**
 * Common base class for ParameterResolver and ReturnTypeResolver.
 * Consolidates type resolution and context lookup logic.
 */
public abstract class AbstractResolver {

    protected static final String OBJECT = "Object";
    protected static final Type VOID_TYPE = new VoidType();
    protected static final Type INT_TYPE = new PrimitiveType(PrimitiveType.Primitive.INT);
    protected static final Type LONG_TYPE = new PrimitiveType(PrimitiveType.Primitive.LONG);
    protected static final Type DOUBLE_TYPE = new PrimitiveType(PrimitiveType.Primitive.DOUBLE);
    protected static final Type BOOLEAN_TYPE = new PrimitiveType(PrimitiveType.Primitive.BOOLEAN);
    protected static final Type CHAR_TYPE = new PrimitiveType(PrimitiveType.Primitive.CHAR);
    protected static final Type STRING_TYPE = new ClassOrInterfaceType(null, "String");
    protected static final Type OBJECT_TYPE = new ClassOrInterfaceType(null, OBJECT);

    protected final Map<String, CompilationUnit> allCUs;
    protected final DataFlowAnalyzer dataFlowAnalyzer;

    protected AbstractResolver(Map<String, CompilationUnit> allCUs, DataFlowAnalyzer dataFlowAnalyzer) {
        this.allCUs = allCUs;
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
                return new ClassOrInterfaceType(null, simplifyType(fqn));
            }
        }

        if (type.isVarType() || "var".equals(type.asString())) {
            if (contextNode instanceof VariableDeclarator v && v.getInitializer().isPresent()) {
                var init = v.getInitializer().get();
                return resolveExpressionTypeToAST(init, sequence);
            }
            return OBJECT_TYPE;
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
        CallableDeclaration<?> callable = sequence.containingCallable();
        if (callable != null) {
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
        }

        // 3. Scan method body for variables declared outside the block
        if (callable != null && sequence.getCallableBody().isPresent()) {
            Optional<Type> type = findVarTypeInStatement(sequence.getCallableBody().get(), varName, sequence);
            if (type.isPresent()) {
                return type.get();
            }
        }

        return OBJECT_TYPE;
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
                        return OBJECT_TYPE;
                    }
                }
                return super.visit(n, name);
            }
        }
        return Optional.ofNullable(node.accept(new VarTypeVisitor(), varName));
    }

    protected Type inferTypeFromMethodCall(com.github.javaparser.ast.expr.MethodCallExpr methodCall, StatementSequence sequence) {
        try {
            ResolvedType resolved = methodCall.calculateResolvedType();
            return convertResolvedTypeToJavaParserType(resolved);
        } catch (Exception e) {
            // Manual lookup via allCUs
            if (methodCall.getScope().isPresent()) {
                String scopeName = methodCall.getScope().get().toString();
                CompilationUnit typeCU = findCompilationUnit(sequence, scopeName);

                if (typeCU != null) {
                    String methodName = methodCall.getNameAsString();
                    return typeCU.findAll(MethodDeclaration.class).stream()
                            .filter(m -> m.getNameAsString().equals(methodName))
                            .findFirst()
                            .map(m -> resolveTypeToAST(m.getType(), m, sequence))
                            .orElse(null);
                }
            } else if (sequence.containingCallable() != null) {
                var classDecl = sequence.containingCallable().findAncestor(ClassOrInterfaceDeclaration.class);
                if (classDecl.isPresent()) {
                    String methodName = methodCall.getNameAsString();
                    return classDecl.get().getMethodsByName(methodName).stream()
                            .findFirst()
                            .map(m -> resolveTypeToAST(m.getType(), m, sequence))
                            .orElse(null);
                }
            }
        }
        return null;
    }

    protected Type inferTypeFromExpression(Expression expr) {
        if (expr.isStringLiteralExpr()) return STRING_TYPE;
        if (expr.isIntegerLiteralExpr()) return INT_TYPE;
        if (expr.isLongLiteralExpr()) return LONG_TYPE;
        if (expr.isDoubleLiteralExpr()) return DOUBLE_TYPE;
        if (expr.isBooleanLiteralExpr()) return BOOLEAN_TYPE;
        if (expr.isCharLiteralExpr()) return CHAR_TYPE;
        if (expr.isObjectCreationExpr()) return expr.asObjectCreationExpr().getType();
        if (expr.isBinaryExpr()) {
            if (expr.toString().contains("\"")) return STRING_TYPE;
            return INT_TYPE;
        }
        return OBJECT_TYPE;
    }

    protected CompilationUnit findCompilationUnit(StatementSequence sequence, String scopeName) {
        Type scopeType = findTypeInContext(sequence, scopeName);
        String typeStr = scopeType.asString();

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

    protected Type convertResolvedTypeToJavaParserType(ResolvedType resolvedType) {
        if (resolvedType == null || resolvedType.isTypeVariable()) {
            return new ClassOrInterfaceType(null, OBJECT);
        }

        try {
            String typeDesc = resolvedType.describe();
            // Handle common generics or internal types that might fail simple parse
            if (typeDesc.contains("<") && !typeDesc.contains("?")) {
                 return StaticJavaParser.parseType(typeDesc);
            }
            return StaticJavaParser.parseType(simplifyType(typeDesc));
        } catch (Exception e) {
            return new ClassOrInterfaceType(null, simplifyType(resolvedType.describe()));
        }
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
