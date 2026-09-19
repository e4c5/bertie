package com.raditha.dedup.clustering;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises the source-level inference fallbacks of {@link AbstractResolver}
 * (used when JavaParser symbol resolution fails, e.g. for project classes that
 * are only available as parsed compilation units).
 */
class AbstractResolverTypeInferenceTest {

    private static class TestResolver extends AbstractResolver {
        TestResolver() {
            super(new DataFlowAnalyzer());
        }

        Type methodCall(MethodCallExpr call, StatementSequence seq) {
            return inferTypeFromMethodCall(call, seq);
        }

        Type expression(Expression expr) {
            return inferTypeFromExpression(expr);
        }

        Type resolved(ResolvedType type) {
            return convertResolvedTypeToJavaParserType(type);
        }
    }

    private static final String BOX = """
            package demo;
            import java.util.List;
            public class Box<T> {
                public T get() { return null; }
                public List<T> all() { return null; }
                public Box<T> self() { return this; }
                public Holder holder() { return null; }
            }
            """;

    private static final String HOLDER = """
            package demo;
            public class Holder {
                public String name() { return ""; }
                public String name(int idx) { return ""; }
                public int size() { return 0; }
            }
            """;

    private TestResolver resolver;
    private ParserConfiguration previousConfiguration;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/analyzer-tests.yml"));
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        previousConfiguration = StaticJavaParser.getParserConfiguration();
        // Parse project classes WITHOUT a symbol solver so calculateResolvedType() fails
        StaticJavaParser.setConfiguration(new ParserConfiguration());
        AntikytheraRunTime.addCompilationUnit("demo.Box", StaticJavaParser.parse(BOX));
        AntikytheraRunTime.addCompilationUnit("demo.Holder", StaticJavaParser.parse(HOLDER));
        resolver = new TestResolver();
    }

    @AfterEach
    void restoreParserConfiguration() {
        StaticJavaParser.setConfiguration(previousConfiguration);
    }

    private record Ctx(StatementSequence seq, MethodCallExpr call) {}

    private Ctx contextFor(String body) {
        String src = """
                package demo;
                import java.util.List;
                class Client {
                    Box<String> field;
                    void m(Box<String> box, Holder h) {
                        Object v = %s;
                    }
                }
                """.formatted(body);
        CompilationUnit cu = StaticJavaParser.parse(src);
        MethodDeclaration m = cu.findFirst(MethodDeclaration.class).get();
        StatementSequence seq = new StatementSequence(m.getBody().get().getStatements(), null, 0, m,
                ContainerType.METHOD, cu, null);
        MethodCallExpr call = cu.findFirst(com.github.javaparser.ast.body.VariableDeclarator.class,
                        d -> d.getNameAsString().equals("v"))
                .get().getInitializer().get().asMethodCallExpr();
        return new Ctx(seq, call);
    }

    @Test
    void classTypeParameterIsSubstitutedFromScope() {
        Ctx ctx = contextFor("box.get()");
        assertEquals("String", resolver.methodCall(ctx.call(), ctx.seq()).asString());
    }

    @Test
    void nestedTypeParameterIsSubstituted() {
        Ctx ctx = contextFor("box.all()");
        assertEquals("List<String>", resolver.methodCall(ctx.call(), ctx.seq()).asString());
    }

    @Test
    void chainedCallsThroughProjectClassesAreResolved() {
        Ctx ctx = contextFor("box.self().holder().size()");
        assertEquals("int", resolver.methodCall(ctx.call(), ctx.seq()).asString());
    }

    @Test
    void chainedCallOnFieldWithThisScope() {
        Ctx ctx = contextFor("this.field.holder().name()");
        assertEquals("String", resolver.methodCall(ctx.call(), ctx.seq()).asString());
    }

    @Test
    void overloadIsSelectedByArity() {
        Ctx ctx = contextFor("h.name(3)");
        Type t = resolver.methodCall(ctx.call(), ctx.seq());
        assertNotNull(t);
        assertEquals("String", t.asString());
    }

    @Test
    void unmatchedArityDoesNotFallBackToArbitraryOverload() {
        Ctx ctx = contextFor("h.name(1, 2, 3)");
        assertNull(resolver.methodCall(ctx.call(), ctx.seq()));
    }

    @Test
    void conditionalExpressionUsesCommonBranchType() {
        assertEquals("double", resolver.expression(StaticJavaParser.parseExpression("c ? 1 : 2.5")).asString());
        assertEquals("long", resolver.expression(StaticJavaParser.parseExpression("c ? 1L : 2")).asString());
        assertEquals("String", resolver.expression(StaticJavaParser.parseExpression("c ? \"a\" : \"b\"")).asString());
        assertEquals("String", resolver.expression(StaticJavaParser.parseExpression("c ? null : \"b\"")).asString());
        assertEquals("Object", resolver.expression(StaticJavaParser.parseExpression("c ? 1 : \"b\"")).asString());
    }

    @Test
    void diamondCreationTakesDeclaredTypeArguments() {
        CompilationUnit cu = StaticJavaParser.parse("""
                import java.util.*;
                class C { void m() { List<String> xs = new ArrayList<>(); } }
                """);
        Expression init = cu.findFirst(com.github.javaparser.ast.body.VariableDeclarator.class)
                .get().getInitializer().get();
        assertEquals("List<String>", resolver.expression(init).asString());
    }

    @Test
    void diamondCreationWithoutContextFallsBackToRawType() {
        Expression init = StaticJavaParser.parseExpression("new java.util.ArrayList<>()");
        assertEquals("java.util.ArrayList", resolver.expression(init).asString());
    }

    @Test
    void binaryExpressionsInferOperatorResultTypes() {
        assertEquals("boolean", resolver.expression(StaticJavaParser.parseExpression("a < b")).asString());
        assertEquals("double", resolver.expression(StaticJavaParser.parseExpression("1 + 2.5")).asString());
        assertEquals("long", resolver.expression(StaticJavaParser.parseExpression("1L * 3")).asString());
        assertEquals("String", resolver.expression(StaticJavaParser.parseExpression("\"a\" + 1")).asString());
        assertEquals("boolean", resolver.expression(StaticJavaParser.parseExpression("!done")).asString());
    }

    @Test
    void resolvedGenericTypesAreSimplifiedRecursively() {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver())));
        CompilationUnit cu = parser.parse("""
                import java.util.*;
                class C {
                    void m(Map<String, List<? extends Number>> m, List<String> l) {
                        Object a = m;
                        Object b = l.iterator();
                        Object c = m.keySet().stream();
                    }
                }
                """).getResult().get();
        var decls = cu.findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
        assertEquals("Map<String,List<? extends Number>>",
                resolver.resolved(decls.get(0).getInitializer().get().calculateResolvedType()).asString());
        assertEquals("Iterator<String>",
                resolver.resolved(decls.get(1).getInitializer().get().calculateResolvedType()).asString());
        assertEquals("Stream<String>",
                resolver.resolved(decls.get(2).getInitializer().get().calculateResolvedType()).asString());
    }
}
