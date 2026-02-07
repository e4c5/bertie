package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.analysis.AnonymousClassDependencyAnalyzer;
import com.raditha.dedup.analysis.AnonymousClassDependencyAnalyzer.DependencyResult;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Refactorer that extracts duplicate anonymous class bodies into a named class.
 * Supports two strategies:
 * - Extract to a new public top-level class
 * - Extract to an inner class on a common parent
 */
public class AnonymousClassExtractor extends AbstractExtractor {

    @Override
    public MethodExtractor.RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        initialize(cluster, recommendation);

        StatementSequence primary = cluster.primary();
        AnonymousClassContext context = AnonymousClassContext.from(primary);
        if (context == null) {
            throw new IllegalArgumentException("No anonymous class context found for cluster");
        }

        if (recommendation.getStrategy() == RefactoringStrategy.EXTRACT_ANONYMOUS_TO_PUBLIC_CLASS) {
            extractToPublicClass(context);
        } else if (recommendation.getStrategy() == RefactoringStrategy.EXTRACT_ANONYMOUS_TO_PARENT_INNER_CLASS) {
            extractToParentInnerClass(context);
        } else {
            throw new UnsupportedOperationException("Unsupported anonymous class strategy: " + recommendation.getStrategy());
        }

        return new MethodExtractor.RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Extracted anonymous class: " + context.className);
    }

    private void extractToPublicClass(AnonymousClassContext context) {
        CompilationUnit newCu = new CompilationUnit();
        if (!packageName.isEmpty()) {
            newCu.setPackageDeclaration(packageName);
        }

        ClassOrInterfaceDeclaration newClass = newCu.addClass(context.className, Modifier.Keyword.PUBLIC);
        addTypeRelationship(newClass, context.anonymousType);
        addAnonymousBodyMembers(newClass, context.anonymousBody);

        // Copy needed imports from primary CU
        context.anonymousMethod.findCompilationUnit().ifPresent(sourceCu -> {
            copyNeededImports(sourceCu, newCu, context.anonymousMethod);
            copyAnonymousTypeImport(sourceCu, newCu, context.anonymousType);
        });

        Path newPath = context.primarySourcePath.getParent().resolve(context.className + ".java");
        newCu.setStorage(newPath);
        modifiedFiles.put(newPath, newCu.toString());

        String fqn = packageName.isEmpty() ? context.className : packageName + "." + context.className;
        AntikytheraRunTime.addCompilationUnit(fqn, newCu);

        replaceAllAnonymousCreations(context, new ObjectCreationExpr(null,
                new ClassOrInterfaceType(null, context.className), new NodeList<>()));

        saveModifiedCompilationUnits();
    }

    private void extractToParentInnerClass(AnonymousClassContext context) {
        DependencyResult deps = AnonymousClassDependencyAnalyzer.analyze(context.anonymousMethod, context.outerClass);
        if (deps.usesOuterMethods()) {
            throw new IllegalArgumentException("Anonymous class depends on outer methods; cannot extract to parent inner class safely");
        }

        String parentClassName = determineParentClassName();
        CompilationUnit parentCu = findOrCreateParentCu(parentClassName, context.primarySourcePath);
        ClassOrInterfaceDeclaration parentClass = findPrimaryClass(parentCu)
                .orElseThrow(() -> new IllegalStateException("Parent class not found in generated CU"));

        Map<String, FieldInfo> fieldsToLift = computeCompatibleFieldsToLift(deps.usedFields());
        liftFieldsToParent(parentClass, fieldsToLift);

        if (parentClass.getMembers().stream()
                .filter(m -> m instanceof ClassOrInterfaceDeclaration)
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .anyMatch(c -> c.getNameAsString().equals(context.className))) {
            // Inner class already present
        } else {
            ClassOrInterfaceDeclaration inner = new ClassOrInterfaceDeclaration();
            inner.setName(context.className);
            inner.addModifier(Modifier.Keyword.PROTECTED);
            parentClass.addMember(inner);
            addTypeRelationship(inner, context.anonymousType);
            addAnonymousBodyMembers(inner, context.anonymousBody);
        }

        Path parentPath = context.primarySourcePath.getParent().resolve(parentClassName + ".java");
        parentCu.setStorage(parentPath);
        modifiedFiles.put(parentPath, parentCu.toString());

        String fqn = packageName.isEmpty() ? parentClassName : packageName + "." + parentClassName;
        AntikytheraRunTime.addCompilationUnit(fqn, parentCu);

        context.anonymousMethod.findCompilationUnit().ifPresent(sourceCu -> {
            copyAnonymousTypeImport(sourceCu, parentCu, context.anonymousType);
        });

        // Update children to extend parent and remove lifted fields
        updateChildrenForParent(parentClassName, fieldsToLift.keySet());

        replaceAllAnonymousCreations(context, new ObjectCreationExpr(
                null,
                new ClassOrInterfaceType(null, context.className),
                new NodeList<>()));

        saveModifiedCompilationUnits();
    }

    private void addTypeRelationship(ClassOrInterfaceDeclaration target, ClassOrInterfaceType anonymousType) {
        // Prefer implements for interface-like usage
        target.addImplementedType(anonymousType.clone());
    }

    private void addAnonymousBodyMembers(ClassOrInterfaceDeclaration target, NodeList<BodyDeclaration<?>> body) {
        boolean hasNonMethodMembers = body.stream()
                .anyMatch(b -> !(b instanceof MethodDeclaration));
        if (hasNonMethodMembers) {
            throw new IllegalArgumentException("Anonymous class contains unsupported members (fields/initializers)");
        }
        for (BodyDeclaration<?> member : body) {
            if (member instanceof MethodDeclaration md) {
                target.addMember(md.clone());
            }
        }
    }

    private void replaceAllAnonymousCreations(AnonymousClassContext context, ObjectCreationExpr replacementTemplate) {
        Set<ObjectCreationExpr> creations = collectAnonymousCreations();
        for (ObjectCreationExpr creation : creations) {
            ObjectCreationExpr replacement = replacementTemplate.clone();
            creation.replace(replacement);
        }
    }

    private Set<ObjectCreationExpr> collectAnonymousCreations() {
        Set<ObjectCreationExpr> creations = new HashSet<>();
        for (StatementSequence seq : cluster.allSequences()) {
            ObjectCreationExpr oc = findEnclosingAnonymousCreation(seq);
            if (oc != null) {
                creations.add(oc);
            }
        }
        return creations;
    }

    private ObjectCreationExpr findEnclosingAnonymousCreation(StatementSequence seq) {
        if (seq.container() == null) {
            return null;
        }
        return seq.container()
                .findAncestor(ObjectCreationExpr.class)
                .filter(oc -> oc.getAnonymousClassBody().isPresent())
                .orElse(null);
    }

    private void saveModifiedCompilationUnits() {
        for (CompilationUnit cu : involvedCus) {
            Path path = cuToPath.get(cu);
            if (path != null) {
                modifiedFiles.put(path, cu.toString());
            }
        }
    }

    private Map<String, FieldInfo> computeCompatibleFieldsToLift(Set<String> fieldNames) {
        Map<String, FieldInfo> lifted = new HashMap<>();
        if (fieldNames.isEmpty()) {
            return lifted;
        }

        List<ClassOrInterfaceDeclaration> classes = new ArrayList<>();
        for (CompilationUnit cu : involvedCus) {
            findPrimaryClass(cu).ifPresent(classes::add);
        }

        for (String name : fieldNames) {
            Type type = null;
            Boolean isStatic = null;
            Boolean isFinal = null;
            boolean compatible = true;
            for (ClassOrInterfaceDeclaration clazz : classes) {
                Optional<FieldDeclaration> field = clazz.getFieldByName(name);
                if (field.isEmpty()) {
                    compatible = false;
                    break;
                }
                Type current = field.get().getElementType();
                if (type == null) {
                    type = current.clone();
                    isStatic = field.get().isStatic();
                    isFinal = field.get().isFinal();
                } else if (!type.toString().equals(current.toString())) {
                    compatible = false;
                    break;
                } else if (!isStatic.equals(field.get().isStatic()) || !isFinal.equals(field.get().isFinal())) {
                    compatible = false;
                    break;
                }
            }
            if (compatible && type != null && isStatic != null && isFinal != null) {
                lifted.put(name, new FieldInfo(type, isStatic, isFinal));
            } else {
                throw new IllegalArgumentException("Field '" + name + "' is not compatible across classes");
            }
        }
        return lifted;
    }

    private void liftFieldsToParent(ClassOrInterfaceDeclaration parentClass, Map<String, FieldInfo> fieldsToLift) {
        if (fieldsToLift.isEmpty()) {
            return;
        }
        CompilationUnit parentCu = parentClass.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("Parent class not in CU"));
        CompilationUnit sourceCu = cluster.primary().compilationUnit();

        for (Map.Entry<String, FieldInfo> entry : fieldsToLift.entrySet()) {
            String name = entry.getKey();
            if (parentClass.getFieldByName(name).isPresent()) {
                continue;
            }
            FieldInfo info = entry.getValue();
            FieldDeclaration field = parentClass.addField(info.type.clone(), name, Modifier.Keyword.PROTECTED);
            if (info.isStatic) {
                field.addModifier(Modifier.Keyword.STATIC);
            }
            if (info.isFinal) {
                field.addModifier(Modifier.Keyword.FINAL);
            }
            findFieldInitializer(sourceCu, name).ifPresent(init -> field.getVariable(0).setInitializer(init.clone()));
            copyFieldImports(sourceCu, parentCu, field);
        }
    }

    private void updateChildrenForParent(String parentClassName, Set<String> liftedFieldNames) {
        for (CompilationUnit cu : involvedCus) {
            Optional<ClassOrInterfaceDeclaration> clazzOpt = findPrimaryClass(cu);
            if (clazzOpt.isEmpty()) {
                continue;
            }
            ClassOrInterfaceDeclaration clazz = clazzOpt.get();
            if (clazz.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().equals(parentClassName))) {
                // already extends
            } else {
                clazz.addExtendedType(parentClassName);
            }

            if (!liftedFieldNames.isEmpty()) {
                for (String fieldName : liftedFieldNames) {
                    clazz.getFieldByName(fieldName).ifPresent(Node::remove);
                }
            }
        }
    }

    private CompilationUnit findOrCreateParentCu(String parentClassName, Path basePath) {
        Path parentPath = basePath.getParent().resolve(parentClassName + ".java");
        if (Files.exists(parentPath)) {
            try {
                return com.github.javaparser.StaticJavaParser.parse(parentPath);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse existing parent class: " + parentClassName, e);
            }
        }
        CompilationUnit cu = new CompilationUnit();
        if (!packageName.isEmpty()) {
            cu.setPackageDeclaration(packageName);
        }
        cu.addClass(parentClassName, Modifier.Keyword.PUBLIC);
        return cu;
    }

    private String determineParentClassName() {
        Set<String> classNames = new java.util.TreeSet<>();
        for (StatementSequence seq : cluster.allSequences()) {
            findPrimaryClass(seq.compilationUnit())
                    .ifPresent(c -> classNames.add(c.getNameAsString()));
        }

        if (classNames.isEmpty()) {
            return "BaseClass";
        }

        String commonSuffix = findCommonSuffix(classNames);
        if (commonSuffix.length() > 3) {
            return "Base" + commonSuffix;
        }

        String firstName = classNames.iterator().next();
        return "Abstract" + firstName;
    }

    private String findCommonSuffix(Set<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        String reference = names.iterator().next();
        for (int len = reference.length(); len > 0; len--) {
            String suffix = reference.substring(reference.length() - len);
            boolean allMatch = true;
            for (String name : names) {
                if (!name.endsWith(suffix)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch && Character.isUpperCase(suffix.charAt(0))) {
                return suffix;
            }
        }
        return "";
    }

    private Optional<Expression> findFieldInitializer(CompilationUnit cu, String fieldName) {
        return cu.findFirst(ClassOrInterfaceDeclaration.class)
                .flatMap(c -> c.getFieldByName(fieldName))
                .flatMap(f -> f.getVariable(0).getInitializer());
    }

    private void copyFieldImports(CompilationUnit source, CompilationUnit target, FieldDeclaration field) {
        copyImportsForType(source, target, field.getElementType());
        field.getVariable(0).getInitializer().ifPresent(init -> {
            for (com.github.javaparser.ast.body.VariableDeclarator vd : field.getVariables()) {
                init.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class)
                        .forEach(t -> copyImportsForType(source, target, t));
            }
        });
    }

    private void copyImportsForType(CompilationUnit source, CompilationUnit target, Type type) {
        if (!type.isClassOrInterfaceType()) {
            return;
        }
        String typeName = type.asClassOrInterfaceType().getNameAsString();
        source.getImports().stream()
                .filter(i -> i.getName().getIdentifier().equals(typeName))
                .filter(i -> target.getImports().stream().noneMatch(existing -> existing.equals(i)))
                .forEach(target::addImport);
    }

    private void copyAnonymousTypeImport(CompilationUnit source, CompilationUnit target, ClassOrInterfaceType type) {
        if (type.getScope().isPresent()) {
            return; // already qualified like Outer.Inner
        }
        String typeName = type.getNameAsString();
        source.getImports().stream()
                .filter(i -> i.getName().getIdentifier().equals(typeName))
                .filter(i -> target.getImports().stream().noneMatch(existing -> existing.equals(i)))
                .forEach(target::addImport);
    }

    private static final class FieldInfo {
        final Type type;
        final boolean isStatic;
        final boolean isFinal;

        FieldInfo(Type type, boolean isStatic, boolean isFinal) {
            this.type = type;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
        }
    }

    private static final class AnonymousClassContext {
        final MethodDeclaration anonymousMethod;
        final NodeList<BodyDeclaration<?>> anonymousBody;
        final ClassOrInterfaceType anonymousType;
        final ClassOrInterfaceDeclaration outerClass;
        final Path primarySourcePath;
        final String className;

        private AnonymousClassContext(MethodDeclaration anonymousMethod,
                                      NodeList<BodyDeclaration<?>> anonymousBody,
                                      ClassOrInterfaceType anonymousType,
                                      ClassOrInterfaceDeclaration outerClass,
                                      Path primarySourcePath,
                                      String className) {
            this.anonymousMethod = anonymousMethod;
            this.anonymousBody = anonymousBody;
            this.anonymousType = anonymousType;
            this.outerClass = outerClass;
            this.primarySourcePath = primarySourcePath;
            this.className = className;
        }

        static AnonymousClassContext from(StatementSequence seq) {
            if (!(seq.containingCallable() instanceof MethodDeclaration method)) {
                return null;
            }
            ObjectCreationExpr oc = method.findAncestor(ObjectCreationExpr.class)
                    .filter(obj -> obj.getAnonymousClassBody().isPresent())
                    .orElse(null);
            if (oc == null || oc.getAnonymousClassBody().isEmpty()) {
                return null;
            }
            ClassOrInterfaceDeclaration outerClass = oc.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
            if (outerClass == null) {
                return null;
            }
            ClassOrInterfaceType type = qualifyTypeIfNested(oc.getType().asClassOrInterfaceType(), outerClass);
            String className = suggestClassName(type, seq.sourceFilePath());
            return new AnonymousClassContext(method, oc.getAnonymousClassBody().get(), type,
                    outerClass, seq.sourceFilePath(), className);
        }

        private static String suggestClassName(ClassOrInterfaceType type, Path sourcePath) {
            String base;
            if (!type.getTypeArguments().isEmpty() && type.getTypeArguments().get().size() == 1
                    && type.getTypeArguments().get().get(0).isClassOrInterfaceType()) {
                String arg = type.getTypeArguments().get().get(0).asClassOrInterfaceType().getNameAsString();
                base = arg + type.getNameAsString();
            } else {
                base = "Extracted" + type.getNameAsString();
            }
            String candidate = base;
            Path dir = sourcePath.getParent();
            int counter = 1;
            while (Files.exists(dir.resolve(candidate + ".java"))) {
                candidate = base + counter;
                counter++;
            }
            return candidate;
        }

        private static ClassOrInterfaceType qualifyTypeIfNested(ClassOrInterfaceType type,
                                                                ClassOrInterfaceDeclaration outerClass) {
            String name = type.getNameAsString();
            boolean isNestedInOuter = outerClass.getMembers().stream()
                    .filter(m -> m instanceof TypeDeclaration)
                    .map(m -> (TypeDeclaration<?>) m)
                    .anyMatch(t -> t.getNameAsString().equals(name));
            if (!isNestedInOuter || type.getScope().isPresent()) {
                return type;
            }
            ClassOrInterfaceType qualified = new ClassOrInterfaceType(null, outerClass.getNameAsString());
            ClassOrInterfaceType result = new ClassOrInterfaceType(qualified, name);
            type.getTypeArguments().ifPresent(args -> result.setTypeArguments(args));
            return result;
        }
    }
}
