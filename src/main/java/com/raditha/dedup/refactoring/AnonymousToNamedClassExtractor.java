package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Extracts duplicate anonymous inner classes into a shared named inner class.
 * When multiple anonymous classes implement the same interface with identical method bodies,
 * this extractor creates a single named inner class and replaces all anonymous instantiations.
 */
public class AnonymousToNamedClassExtractor extends AbstractExtractor {
    private static final Logger logger = LoggerFactory.getLogger(AnonymousToNamedClassExtractor.class);

    @Override
    public MethodExtractor.RefactoringResult refactor(
            DuplicateCluster cluster, RefactoringRecommendation recommendation) {

        initialize(cluster, recommendation);

        // 1. Collect all ObjectCreationExpr nodes that create anonymous classes
        List<ObjectCreationExpr> anonymousCreations = collectAnonymousCreations(cluster);
        if (anonymousCreations.size() < 2) {
            return skipResult("Need at least 2 anonymous class instances");
        }

        // 2. Resolve the implemented type (interface/superclass)
        ClassOrInterfaceType implementedType = anonymousCreations.get(0).getType();

        // 3. Verify all anonymous classes have compatible bodies
        ObjectCreationExpr representative = anonymousCreations.get(0);
        if (!allBodiesCompatible(anonymousCreations)) {
            return skipResult("Anonymous class bodies are not structurally compatible");
        }

        // 4. Find the outer class where the named inner class will be placed
        CompilationUnit primaryCu = cluster.primary().compilationUnit();
        ClassOrInterfaceDeclaration outerClass = findOuterClass(primaryCu, representative);
        if (outerClass == null) {
            return skipResult("Could not find outer class for anonymous class");
        }

        // 5. Generate a unique name for the inner class
        String innerClassName = generateInnerClassName(implementedType, outerClass);

        // 6. Detect captured variables from the enclosing scope
        Set<CapturedVariable> capturedVars = detectCapturedVariables(representative, outerClass);

        // 7. Determine if the inner class should be static
        boolean isStatic = cluster.allSequences().stream().allMatch(StatementSequence::isStaticContext);

        // 8. Build the named inner class
        ClassOrInterfaceDeclaration namedClass = buildNamedInnerClass(
                innerClassName, implementedType, representative, capturedVars, isStatic);

        // 9. Add the named inner class to the outer class
        outerClass.addMember(namedClass);

        // 10. Replace all anonymous class instantiations
        for (ObjectCreationExpr creation : anonymousCreations) {
            replaceAnonymousCreation(creation, innerClassName, capturedVars);
        }

        // 11. Record modified file
        Path sourceFile = cluster.primary().sourceFilePath();
        modifiedFiles.put(sourceFile, primaryCu.toString());

        return new MethodExtractor.RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Replaced " + anonymousCreations.size() + " anonymous classes with named inner class: " + innerClassName);
    }

    /**
     * Collect all ObjectCreationExpr nodes that contain anonymous classes from the cluster sequences.
     */
    private List<ObjectCreationExpr> collectAnonymousCreations(DuplicateCluster cluster) {
        Set<ObjectCreationExpr> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ObjectCreationExpr> result = new ArrayList<>();

        for (StatementSequence seq : cluster.allSequences()) {
            if (seq.containerType() != ContainerType.ANONYMOUS_CLASS_METHOD) {
                continue;
            }
            Node container = seq.container();
            if (container == null) continue;

            container.findAncestor(ObjectCreationExpr.class)
                    .filter(oce -> oce.getAnonymousClassBody().isPresent())
                    .ifPresent(oce -> {
                        if (seen.add(oce)) {
                            result.add(oce);
                        }
                    });
        }
        return result;
    }

    /**
     * Check that all anonymous classes have compatible method bodies.
     * They must all implement the same set of methods with the same signatures.
     */
    private boolean allBodiesCompatible(List<ObjectCreationExpr> creations) {
        if (creations.size() < 2) return false;

        List<String> referenceSignatures = getMethodSignatures(creations.get(0));
        for (int i = 1; i < creations.size(); i++) {
            List<String> signatures = getMethodSignatures(creations.get(i));
            if (!referenceSignatures.equals(signatures)) {
                return false;
            }
        }
        return true;
    }

    private List<String> getMethodSignatures(ObjectCreationExpr oce) {
        List<String> signatures = new ArrayList<>();
        oce.getAnonymousClassBody().ifPresent(body -> {
            for (BodyDeclaration<?> member : body) {
                if (member instanceof MethodDeclaration md) {
                    signatures.add(md.getSignature().asString());
                }
            }
        });
        Collections.sort(signatures);
        return signatures;
    }

    /**
     * Find the outer class declaration that contains the anonymous class.
     * Skips the anonymous class's own implicit type and finds the real enclosing class.
     */
    private ClassOrInterfaceDeclaration findOuterClass(CompilationUnit cu, ObjectCreationExpr anonymousCreation) {
        // Walk up from the anonymous creation to find the enclosing named class
        return anonymousCreation.findAncestor(ClassOrInterfaceDeclaration.class)
                .orElse(cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));
    }

    /**
     * Generate a unique class name for the named inner class.
     */
    private String generateInnerClassName(ClassOrInterfaceType implementedType,
                                          ClassOrInterfaceDeclaration outerClass) {
        String baseName = "Default" + implementedType.getNameAsString();
        String candidate = baseName;
        int suffix = 2;

        Set<String> existingNames = new HashSet<>();
        outerClass.getMembers().forEach(member -> {
            if (member instanceof TypeDeclaration<?> td) {
                existingNames.add(td.getNameAsString());
            }
        });

        while (existingNames.contains(candidate)) {
            candidate = baseName + suffix++;
        }
        return candidate;
    }

    /**
     * Detect variables captured from the enclosing scope by the anonymous class.
     */
    private Set<CapturedVariable> detectCapturedVariables(ObjectCreationExpr oce,
                                                          ClassOrInterfaceDeclaration outerClass) {
        Set<CapturedVariable> captured = new LinkedHashSet<>();
        if (oce.getAnonymousClassBody().isEmpty()) return captured;

        // Collect names declared within the anonymous class body (fields, local vars, params)
        Set<String> declaredInAnonymous = new HashSet<>();
        oce.getAnonymousClassBody().get().forEach(member -> {
            if (member instanceof FieldDeclaration fd) {
                fd.getVariables().forEach(v -> declaredInAnonymous.add(v.getNameAsString()));
            }
            if (member instanceof MethodDeclaration md) {
                md.getParameters().forEach(p -> declaredInAnonymous.add(p.getNameAsString()));
                md.getBody().ifPresent(body ->
                        body.findAll(VariableDeclarator.class)
                                .forEach(v -> declaredInAnonymous.add(v.getNameAsString())));
            }
        });

        // Collect outer class field names
        Set<String> outerFieldNames = new HashSet<>();
        outerClass.getFields().forEach(f ->
                f.getVariables().forEach(v -> outerFieldNames.add(v.getNameAsString())));

        // Collect outer class method names
        Set<String> outerMethodNames = new HashSet<>();
        outerClass.getMethods().forEach(m -> outerMethodNames.add(m.getNameAsString()));

        // Find NameExpr references that are not declared in the anonymous class and not outer fields/methods
        oce.getAnonymousClassBody().get().forEach(member -> {
            member.findAll(NameExpr.class).forEach(nameExpr -> {
                String name = nameExpr.getNameAsString();
                if (!declaredInAnonymous.contains(name) && !outerFieldNames.contains(name)
                        && !outerMethodNames.contains(name) && !isClassName(name)) {
                    // This is a captured local variable - need to find its type
                    findVariableType(oce, name).ifPresent(type ->
                            captured.add(new CapturedVariable(name, type)));
                }
            });
        });

        return captured;
    }

    private boolean isClassName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    /**
     * Find the type of a variable by searching the enclosing scope.
     */
    private Optional<String> findVariableType(ObjectCreationExpr oce, String varName) {
        // Search upward for variable declarations or parameters
        Node current = oce.getParentNode().orElse(null);
        while (current != null) {
            // Check variable declarations in block statements
            if (current instanceof BlockStmt block) {
                for (var stmt : block.getStatements()) {
                    for (VariableDeclarator vd : stmt.findAll(VariableDeclarator.class)) {
                        if (vd.getNameAsString().equals(varName)) {
                            return Optional.of(vd.getType().asString());
                        }
                    }
                }
            }
            // Check method parameters
            if (current instanceof MethodDeclaration md) {
                for (Parameter p : md.getParameters()) {
                    if (p.getNameAsString().equals(varName)) {
                        return Optional.of(p.getType().asString());
                    }
                }
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.of("Object"); // fallback
    }

    /**
     * Build the named inner class AST node.
     */
    private ClassOrInterfaceDeclaration buildNamedInnerClass(
            String className,
            ClassOrInterfaceType implementedType,
            ObjectCreationExpr representative,
            Set<CapturedVariable> capturedVars,
            boolean isStatic) {

        ClassOrInterfaceDeclaration innerClass = new ClassOrInterfaceDeclaration();
        innerClass.setName(className);
        innerClass.setPublic(false);
        innerClass.setPrivate(true);
        if (isStatic) {
            innerClass.addModifier(Modifier.Keyword.STATIC);
        }

        // Add implements clause
        innerClass.addImplementedType(implementedType.clone());

        // Add captured variable fields and constructor if needed
        if (!capturedVars.isEmpty()) {
            addCapturedVariableSupport(innerClass, capturedVars);
        }

        // Copy all methods from the representative anonymous class
        representative.getAnonymousClassBody().ifPresent(body -> {
            for (BodyDeclaration<?> member : body) {
                if (member instanceof MethodDeclaration md) {
                    MethodDeclaration cloned = md.clone();
                    // Ensure @Override is present
                    boolean hasOverride = cloned.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals("Override"));
                    if (!hasOverride) {
                        cloned.addAnnotation("Override");
                    }
                    innerClass.addMember(cloned);
                } else if (member instanceof FieldDeclaration fd) {
                    innerClass.addMember(fd.clone());
                }
            }
        });

        return innerClass;
    }

    /**
     * Add fields and constructor for captured variables.
     */
    private void addCapturedVariableSupport(ClassOrInterfaceDeclaration innerClass,
                                            Set<CapturedVariable> capturedVars) {
        // Add private final fields
        for (CapturedVariable cv : capturedVars) {
            innerClass.addField(cv.type, cv.name, Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);
        }

        // Add constructor
        var constructor = innerClass.addConstructor();
        BlockStmt body = new BlockStmt();
        for (CapturedVariable cv : capturedVars) {
            constructor.addParameter(cv.type, cv.name);
            body.addStatement(com.github.javaparser.StaticJavaParser.parseStatement(
                    "this." + cv.name + " = " + cv.name + ";"));
        }
        constructor.setBody(body);
    }

    /**
     * Replace an anonymous class creation with an instantiation of the named inner class.
     */
    private void replaceAnonymousCreation(ObjectCreationExpr original,
                                          String innerClassName,
                                          Set<CapturedVariable> capturedVars) {
        // Create: new InnerClassName(capturedArgs...)
        ObjectCreationExpr replacement = new ObjectCreationExpr();
        replacement.setType(innerClassName);

        // Add captured variable arguments
        NodeList<com.github.javaparser.ast.expr.Expression> args = new NodeList<>();
        for (CapturedVariable cv : capturedVars) {
            args.add(new NameExpr(cv.name));
        }
        replacement.setArguments(args);

        // Replace in the AST
        original.replace(replacement);
    }

    private MethodExtractor.RefactoringResult skipResult(String reason) {
        return new MethodExtractor.RefactoringResult(
                Map.of(),
                recommendation.getStrategy(),
                "Skipped: " + reason);
    }

    /**
     * Captured variable from the enclosing scope.
     */
    private record CapturedVariable(String name, String type) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CapturedVariable cv)) return false;
            return name.equals(cv.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }
}
