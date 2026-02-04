package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;

import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SuperExpr;
import org.jspecify.annotations.NonNull;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.Reflect;
import java.nio.file.Path;
import java.util.*;

/**
 * Refactorer that extracts cross-class duplicate methods into a common parent
 * class.
 * Child classes are modified to extend the new parent, inheriting the shared
 * method.
 */
public class ParentClassExtractor extends AbstractExtractor {

    private String parentClassName;
    private Map<ParameterSpec, String> paramNameOverrides = new HashMap<>();
    private final Map<String, Type> fieldsToExtract = new HashMap<>();
    private FunctionalPredicateInfo functionalPredicate;

    private CallableDeclaration<?> getCallableToExtract(StatementSequence primary) {
        CallableDeclaration<?> callable = primary.containingCallable();

        if (callable == null) {
            throw new IllegalArgumentException("No containing callable found");
        }

        return callable;
    }

    @Override
    public MethodExtractor.RefactoringResult refactor(
            DuplicateCluster cluster, RefactoringRecommendation recommendation) {

        initialize(cluster, recommendation);

        StatementSequence primary = cluster.primary();
        CompilationUnit primaryCu = primary.compilationUnit();
        CallableDeclaration<?> callableToExtract = getCallableToExtract(primary);

        this.parentClassName = determineParentClassName();
        this.functionalPredicate = buildFunctionalPredicateInfo();

        validateNoInnerClassUsage(primaryCu, callableToExtract);
        validateNoFieldUsage(callableToExtract);
        validateInheritance();
        validateStructuralCompatibility(primaryCu, callableToExtract);

        computeParamNameOverrides(callableToExtract);

        Optional<ClassOrInterfaceDeclaration> existingParent = findExistingCommonParent();
        Optional<CompilationUnit> peerParentCu = findPeerParent();
        boolean isPeerParent = peerParentCu.isPresent();


        if (existingParent.isPresent()) {
            addMethodToExistingParent(existingParent.get(), callableToExtract);
            addFieldsToParent(existingParent.get(), primaryCu);
            
            existingParent.get().findCompilationUnit().ifPresent(parentCu -> {
                Path parentPath = cluster.primary().sourceFilePath().getParent()
                        .resolve(existingParent.get().getNameAsString() + ".java");
                modifiedFiles.put(parentPath, parentCu.toString());
            });
        } else if (!isPeerParent) {
            CompilationUnit parentCu = createParentClass(callableToExtract);

            // Set storage immediately
            Path parentPath = primary.sourceFilePath().getParent().resolve(parentClassName + ".java");
            parentCu.setStorage(parentPath);

            String parentFqn = packageName.isEmpty() ? parentClassName : packageName + "." + parentClassName;
            AntikytheraRunTime.addCompilationUnit(parentFqn, parentCu);
            
            // Register the type wrapper so AbstractCompiler can find it
             findPrimaryClass(parentCu).ifPresent(parentClass -> {
                sa.com.cloudsolutions.antikythera.generator.TypeWrapper wrapper = 
                    new sa.com.cloudsolutions.antikythera.generator.TypeWrapper(parentClass);
                AntikytheraRunTime.addType(parentFqn, wrapper);
                
                addFieldsToParent(parentClass, primaryCu);
                modifiedFiles.put(parentPath, parentCu.toString());
            });
        }
        
        MethodDeclaration extractedMethod = createMethod(callableToExtract);
        for (CompilationUnit currentCu : involvedCus) {
            processInvolvedCompilationUnit(currentCu, existingParent, isPeerParent, peerParentCu, extractedMethod);
        }

        return new MethodExtractor.RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Extracted to parent class: " + parentClassName);
    }

    private void processInvolvedCompilationUnit(CompilationUnit currentCu, Optional<ClassOrInterfaceDeclaration> existingParent,
                                               boolean isPeerParent, Optional<CompilationUnit> peerParentCu,
                                               MethodDeclaration extractedMethod) {
        boolean isCurrentCuParent = isPeerParent && currentCu == peerParentCu.get();

        if (existingParent.isEmpty() && !isCurrentCuParent) {
            addExtendsClause(currentCu);
            addImportIfNeeded(currentCu);
        }

        if (!isCurrentCuParent) {
            removeExtractedFields(currentCu);
        }

        handleMethodRemoval(currentCu, isCurrentCuParent, extractedMethod);
        modifiedFiles.put(cuToPath.get(currentCu), currentCu.toString());
    }

    private void handleMethodRemoval(CompilationUnit currentCu, boolean isCurrentCuParent, MethodDeclaration extractedMethod) {
        List<CallableDeclaration<?>> methods = methodsToRemove.get(currentCu);
        if (methods == null) return;

        for (CallableDeclaration<?> mToRemove : methods) {
            if (isCurrentCuParent) {
                promoteVisibilityIfNeeded(mToRemove);
            } else {
                processChildMethod(mToRemove, extractedMethod);
            }
        }
    }

    private void promoteVisibilityIfNeeded(CallableDeclaration<?> mToRemove) {
        if (mToRemove instanceof MethodDeclaration md) {
            if (md.isPrivate()) {
                md.setModifiers(Modifier.Keyword.PROTECTED);
            } else if (md.isPublic()) {
                md.setModifiers(Modifier.Keyword.PUBLIC);
            }
        }
    }

    private void addImportIfNeeded(CompilationUnit currentCu) {
        String currentPackage = getPackageName(currentCu);
        if (!currentPackage.equals(packageName) && !packageName.isEmpty()) {
            currentCu.addImport(packageName + "." + parentClassName);
        }
    }

    private void processChildMethod(CallableDeclaration<?> methodToRemove,
            MethodDeclaration methodToExtract) {
        String parentMethodName = methodToExtract.getNameAsString();

        boolean hasAnnotations = !methodToRemove.getAnnotations().isEmpty();

        boolean signaturesMatch = signaturesMatch(methodToExtract, methodToRemove);
        long addedParamsCount = recommendation.getSuggestedParameters().stream()
                .filter(p -> {
                    String targetName = paramNameOverrides.getOrDefault(p, p.getName());
                    return methodToExtract.getParameters().stream()
                            .noneMatch(mp -> mp.getNameAsString().equals(targetName));
                }).count();

        boolean isFunctional = functionalPredicate.enabled;

        if (signaturesMatch && addedParamsCount == 0 && !hasAnnotations && !isFunctional) {
            // Only remove if signature matches, no extra params added, and no annotations to preserve
            methodToRemove.remove();
        } else {
            // Keep as thin wrapper - either names differ, params differ, or has annotations
            BlockStmt body = new BlockStmt();
            if (functionalPredicate.enabled) {
                 parentMethodName = functionalPredicate.parentMethodName;
            }
            MethodCallExpr call = new MethodCallExpr(new SuperExpr(), parentMethodName);

            methodToRemove.getParameters().forEach(p -> call.addArgument(p.getNameAsExpression()));
            addLiteralArguments(call, methodToRemove);
            addFunctionalArguments(call, methodToRemove);

            if (methodToRemove instanceof MethodDeclaration md && md.getType().isVoidType()) {
                body.addStatement(call);
            } else if (methodToRemove instanceof ConstructorDeclaration) {
                body.addStatement(call);
            } else {
                body.addStatement(new ReturnStmt(call));
            }
            
            if (methodToRemove instanceof MethodDeclaration md) {
                md.setBody(body);
            } else if (methodToRemove instanceof ConstructorDeclaration cd) {
                cd.setBody(body);
            }
        }
    }

    private boolean signaturesMatch(MethodDeclaration m1, CallableDeclaration<?> m2) {
        if (!(m2 instanceof MethodDeclaration)) return false;
        MethodDeclaration md2 = (MethodDeclaration) m2;
        if (!m1.getNameAsString().equals(md2.getNameAsString())) return false;
        if (m1.getParameters().size() != md2.getParameters().size()) return false;
        for (int i = 0; i < m1.getParameters().size(); i++) {
            String t1 = m1.getParameter(i).getType().asString();
            String t2 = md2.getParameter(i).getType().asString();
            // Ignoring generics nuances for now, just string compare
            if (!t1.equals(t2)) return false; 
        }
        return true;
    }


    private void validateInheritance() {
        Set<String> parentNames = new HashSet<>();
        for (StatementSequence seq : cluster.allSequences()) {
            findPrimaryClass(seq.compilationUnit()).ifPresent(classDecl -> {
                var extendedTypes = classDecl.getExtendedTypes();
                if (!extendedTypes.isEmpty()) {
                    parentNames.add(extendedTypes.get(0).getNameAsString());
                } else {
                    parentNames.add("");
                }
            });
        }

        if (parentNames.size() > 1) {
            throw new IllegalStateException("Skipped: Involved classes have conflicting inheritance hierarchies.");
        }
    }

    private void validateStructuralCompatibility(CompilationUnit cu, CallableDeclaration<?> callable) {
        Optional<ClassOrInterfaceDeclaration> primaryClassOpt = findPrimaryClass(cu);

        if (primaryClassOpt.isEmpty()) {
             throw new IllegalStateException("Skipped: No primary class found.");
        }

        ClassOrInterfaceDeclaration primaryClass = primaryClassOpt.get();
        // Check if callable is a direct member of primaryClass
        // We use reference equality on the parent node
        Optional<Node> parent = callable.getParentNode();
        if (parent.isEmpty() || parent.get() != primaryClass) {
             throw new IllegalStateException("Skipped: Method is in a nested type (Enum, Inner Class) or not a direct member of the primary class.");
        }
    }

    private Optional<CompilationUnit> findPeerParent() {
        for (StatementSequence seq : cluster.allSequences()) {
            Optional<ClassOrInterfaceDeclaration> classDecl = findPrimaryClass(seq.compilationUnit());
            if (classDecl.isPresent() && classDecl.get().getNameAsString().equals(parentClassName)) {
                return Optional.of(seq.compilationUnit());
            }
        }
        return Optional.empty();
    }

    private CompilationUnit createParentClass(CallableDeclaration<?> originalMethod) {
        CompilationUnit parentCu = new CompilationUnit();
        if (!packageName.isEmpty()) {
            parentCu.setPackageDeclaration(packageName);
        }

        MethodDeclaration newMethod = createMethod(originalMethod);

        // Add Predicate import if we introduced it in the transformation
        if (newMethod.getParameters().stream().anyMatch(p -> p.getType().asString().startsWith("Predicate"))) {
            parentCu.addImport("java.util.function.Predicate");
        }

        ClassOrInterfaceDeclaration parentClass = parentCu.addClass(parentClassName)
                .setPublic(true)
                .setAbstract(true)
                .setJavadocComment("Common parent class for shared functionality.");

        if (!methodExists(parentClass, newMethod)) {
            parentClass.addMember(newMethod);
        }

        CompilationUnit originalCu = originalMethod.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("Original method not part of a CompilationUnit"));
        copyNeededImports(originalCu, parentCu, originalMethod);

        return parentCu;
    }

    private @NonNull MethodDeclaration createMethod(CallableDeclaration<?> originalMethod) {
        MethodDeclaration newMethod = createMethodDeclaration(originalMethod);
        newMethod.getAnnotations().clear();
        configureMethodModifiers(originalMethod, newMethod);

        Set<String> declaredVars = new HashSet<>();
        newMethod.getBody().ifPresent(body -> body.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .forEach(v -> declaredVars.add(v.getNameAsString())));

        Map<ParameterSpec, String> paramNameOverrides = MethodExtractor.computeParamNameOverridesStatic(
                declaredVars, recommendation.getSuggestedParameters());

        addSuggestedParameters(newMethod, paramNameOverrides);

        if (newMethod.getBody().isPresent()) {
            BlockStmt body = newMethod.getBody().get();
            for (int i = 0; i < body.getStatements().size(); i++) {
                Statement stmt = body.getStatements().get(i);
                
                // Filter out parameters that are actually fields we are extracting
                com.raditha.dedup.model.RefactoringRecommendation filteredRec = recommendation;
                if (!fieldsToExtract.isEmpty()) {
                    List<com.raditha.dedup.model.ParameterSpec> filteredParams = recommendation.getSuggestedParameters().stream()
                            .filter(p -> !fieldsToExtract.containsKey(paramNameOverrides.getOrDefault(p, p.getName())))
                            .toList();
                    filteredRec = new com.raditha.dedup.model.RefactoringRecommendation(
                            recommendation.getStrategy(),
                            recommendation.getSuggestedMethodName(),
                            filteredParams,
                            recommendation.getSuggestedReturnType(),
                            recommendation.getTargetLocation(),
                            recommendation.getConfidenceScore(),
                            recommendation.getEstimatedLOCReduction(),
                            recommendation.getPrimaryReturnVariable()
                    );
                }
                
                body.getStatements().set(i, MethodExtractor.substituteParametersStatic(
                        stmt, filteredRec, paramNameOverrides));
            }

        }
        
        if (functionalPredicate.enabled) {
            newMethod.setName(functionalPredicate.parentMethodName);
            newMethod.addParameter(functionalPredicate.parameterType, functionalPredicate.parameterName);
            
            // Replace specific check with filter.test(target)
            newMethod.findAll(MethodCallExpr.class).stream()
                .filter(mce -> functionalPredicate.methodNames.contains(mce.getNameAsString()))
                .filter(mce -> mce.getScope().isPresent())
                .filter(mce -> functionalPredicate.scopeName.equals(mce.getScope().get().toString()))
                .forEach(mce -> {
                    MethodCallExpr filterTest = new MethodCallExpr(new NameExpr(functionalPredicate.parameterName), "test");
                    filterTest.addArgument(mce.getScope().get());
                    mce.replace(filterTest);
                });
        }
        
        return newMethod;
    }

    /**
     * Add extends clause to a class.
     */
    private void addExtendsClause(CompilationUnit cu) {
        findPrimaryClass(cu).ifPresent(classDecl -> {
            if (classDecl.getExtendedTypes().isEmpty()) {
                classDecl.addExtendedType(new ClassOrInterfaceType(null, parentClassName));
            }
        });
    }

    private Optional<ClassOrInterfaceDeclaration> findExistingCommonParent() {
        Set<String> parentNames = new HashSet<>();

        for (StatementSequence seq : cluster.allSequences()) {
            Optional<ClassOrInterfaceDeclaration> classDecl = findPrimaryClass(seq.compilationUnit());
            if (classDecl.isPresent()) {
                var extendedTypes = classDecl.get().getExtendedTypes();
                if (!extendedTypes.isEmpty()) {
                    parentNames.add(extendedTypes.get(0).getNameAsString());
                } else {
                    return Optional.empty();
                }
            }
        }

        if (parentNames.size() == 1) {
            String parentName = parentNames.iterator().next();
            for (CompilationUnit cu : involvedCus) {
                Optional<ClassOrInterfaceDeclaration> parentDecl = cu.findAll(ClassOrInterfaceDeclaration.class)
                        .stream()
                        .filter(c -> c.getNameAsString().equals(parentName))
                        .findFirst();
                if (parentDecl.isPresent()) {
                    return parentDecl;
                }
            }

            String packageName = cluster.primary().compilationUnit().getPackageDeclaration()
                    .map(p -> p.getNameAsString())
                    .orElseGet(() -> involvedCus.stream()
                            .map(cu -> cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""))
                            .filter(p -> !p.isEmpty())
                            .findFirst()
                            .orElse(""));
            String parentFqn = packageName.isEmpty() ? parentName : packageName + "." + parentName;
            Map<String, CompilationUnit> resolvedCus = AntikytheraRunTime.getResolvedCompilationUnits();
            if (resolvedCus.containsKey(parentFqn)) {
                CompilationUnit parentCu = AntikytheraRunTime.getCompilationUnit(parentFqn);
                if (parentCu != null) {
                    Optional<ClassOrInterfaceDeclaration> parentDecl = parentCu.findAll(ClassOrInterfaceDeclaration.class)
                            .stream()
                            .filter(c -> c.getNameAsString().equals(parentName))
                            .findFirst();
                    if (parentDecl.isPresent()) {
                        return parentDecl;
                    }
                }
            }
        }

        return Optional.empty();
    }

    private void addMethodToExistingParent(ClassOrInterfaceDeclaration parentClass,
            CallableDeclaration<?> method) {
        MethodDeclaration newMethod = createMethod(method);

        if (!methodExists(parentClass, newMethod)) {
            parentClass.addMember(newMethod);
        }

        CompilationUnit parentCu = parentClass.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("Parent class not part of a CompilationUnit"));
        method.findCompilationUnit().ifPresent(methodCu -> copyNeededImports(methodCu, parentCu, method));
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
        if (names.isEmpty())
            return "";

        Iterator<String> iterator = names.iterator();
        String reference = iterator.next();

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

    private void validateNoInnerClassUsage(CompilationUnit cu, CallableDeclaration<?> method) {
        List<String> innerClassNames = new ArrayList<>();

        // Find all nested types
        cu.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
                .filter(TypeDeclaration::isNestedType)
                .forEach(t -> innerClassNames.add(t.getNameAsString()));

        if (innerClassNames.isEmpty()) {
            return;
        }

        // Check for usage in method (Types, Constructors)
        // Check ClassOrInterfaceType
        for (ClassOrInterfaceType type : method.findAll(ClassOrInterfaceType.class)) {
            if (innerClassNames.contains(type.getNameAsString())) {
                throw new IllegalStateException("Skipped: Method uses inner class '" + type.getNameAsString()
                        + "' which cannot be extracted to parent class.");
            }
        }
    }

    private void validateNoFieldUsage(CallableDeclaration<?> method) {
        Set<String> allClassFieldNames = collectClassFieldNames();
        Set<String> usedFieldNames = identifyUsedFieldNames(method, allClassFieldNames);

        for (String fieldName : usedFieldNames) {
            checkFieldCompatibility(fieldName);
        }
    }

    private Set<String> collectClassFieldNames() {
        Set<String> allClassFieldNames = new HashSet<>();
        for (CompilationUnit involvedCu : involvedCus) {
            findPrimaryClass(involvedCu).ifPresent(c ->
                    c.getFields().forEach(f -> f.getVariables().forEach(v -> allClassFieldNames.add(v.getNameAsString())))
            );
        }
        findExistingCommonParent().ifPresent(c ->
                c.getFields().forEach(f -> f.getVariables().forEach(v -> allClassFieldNames.add(v.getNameAsString())))
        );
        return allClassFieldNames;
    }

    private Set<String> identifyUsedFieldNames(CallableDeclaration<?> method, Set<String> allClassFieldNames) {
        Set<String> usedFieldNames = new HashSet<>();
        method.findAll(NameExpr.class).forEach(name -> {
            String identifier = name.getNameAsString();
            if (allClassFieldNames.contains(identifier) && !isShadowed(identifier, method)) {
                usedFieldNames.add(identifier);
            }
        });
        method.findAll(FieldAccessExpr.class).forEach(fieldAccess -> {
            if (fieldAccess.getScope() instanceof ThisExpr || fieldAccess.getScope() instanceof SuperExpr) {
                String fieldName = fieldAccess.getNameAsString();
                if (allClassFieldNames.contains(fieldName) && !isShadowed(fieldName, method)) {
                    usedFieldNames.add(fieldName);
                }
            }
        });
        return usedFieldNames;
    }

    private void checkFieldCompatibility(String fieldName) {
        Type type = null;
        boolean extractable = true;

        for (CompilationUnit involvedCu : involvedCus) {
            Optional<ClassOrInterfaceDeclaration> classDecl = findPrimaryClass(involvedCu);
            if (classDecl.isPresent() && classDecl.get().getNameAsString().equals(parentClassName)) {
                continue;
            }

            if (classDecl.isEmpty()) {
                extractable = false;
                break;
            }

            Optional<com.github.javaparser.ast.body.FieldDeclaration> field = classDecl.get().getFieldByName(fieldName);
            if (field.isEmpty()) {
                Optional<ClassOrInterfaceDeclaration> parentRef = findExistingCommonParent();
                if (parentRef.isPresent() && parentRef.get().getFieldByName(fieldName).isPresent()) {
                    if (type == null) {
                        type = parentRef.get().getFieldByName(fieldName).get().getElementType();
                    }
                    continue;
                }
                extractable = false;
                break;
            }

            Type currentType = field.get().getElementType();
            if (type == null) {
                type = currentType;
            } else if (!type.asString().equals(currentType.asString())) {
                extractable = false;
                break;
            }
        }

        if (extractable && type != null) {
            fieldsToExtract.put(fieldName, type.clone());
        } else {
            throw new IllegalStateException(
                    "Skipped: Method uses field '" + fieldName + "' which is not present or compatible in all classes.");
        }
    }

    private void addFieldsToParent(ClassOrInterfaceDeclaration parentClass, CompilationUnit sourceCu) {
        for (Map.Entry<String, Type> entry : fieldsToExtract.entrySet()) {
            String fieldName = entry.getKey();
            Type fieldType = entry.getValue();
            if (parentClass.getFieldByName(fieldName).isEmpty()) {
                com.github.javaparser.ast.body.FieldDeclaration field = parentClass.addField(fieldType.clone(), fieldName, Modifier.Keyword.PROTECTED);
                parentClass.findCompilationUnit().ifPresent(parentCu -> 
                    copyFieldImports(sourceCu, parentCu, field)
                );
            }
        }
    }

    private void copyFieldImports(CompilationUnit source, CompilationUnit target, com.github.javaparser.ast.body.FieldDeclaration field) {
        copyImportsForType(source, target, field.getElementType());
    }

    private void copyImportsForType(CompilationUnit source, CompilationUnit target, Type type) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType ciType = type.asClassOrInterfaceType();
            String name = ciType.getNameAsString();
            source.getImports().stream()
                .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getNameAsString().endsWith("." + name))
                .findFirst()
                .ifPresent(i -> target.addImport(i.getNameAsString()));
            
            ciType.getTypeArguments().ifPresent(args -> args.forEach(arg -> copyImportsForType(source, target, arg)));
        } else if (type.isArrayType()) {
             copyImportsForType(source, target, type.asArrayType().getComponentType());
        }
    }

    private void removeExtractedFields(CompilationUnit cu) {
        findPrimaryClass(cu).ifPresent(classDecl -> {
            for (String fieldName : fieldsToExtract.keySet()) {
                classDecl.getFieldByName(fieldName).ifPresent(Node::remove);
            }
        });
    }

    /**
     * Check if a name expression is shadowed by a local variable or parameter.
     */

    private boolean isShadowed(String identifier, CallableDeclaration<?> method) {
        // Check parameters
        boolean isParam = method.getParameters().stream()
                .anyMatch(p -> p.getNameAsString().equals(identifier));
        if (isParam)
            return true;

        return getCallableBody(method).map(body -> body.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .stream()
                .anyMatch(v -> v.getNameAsString().equals(identifier)))
                .orElse(false);
    }

    private void addLiteralArguments(MethodCallExpr call, CallableDeclaration<?> childMethod) {
        Optional<StatementSequence> matchingSeq = cluster.allSequences().stream()
                .filter(seq -> seq.containingCallable().getNameAsString().equals(childMethod.getNameAsString()) &&
                        seq.sourceFilePath().equals(childMethod.findCompilationUnit()
                                .map(com.raditha.dedup.util.ASTUtility::getSourcePath)
                                .orElse(null)))
                .findFirst();

        recommendation.getSuggestedParameters().forEach(param -> {
            String targetName = paramNameOverrides.getOrDefault(param, param.getName());
            if (fieldsToExtract.containsKey(targetName)) return;

            boolean exists = childMethod.getParameters().stream()
                    .anyMatch(p -> p.getNameAsString().equals(targetName));
            if (exists) return;

            Expression val = determineParameterValue(matchingSeq, param, childMethod);
            if (val != null) {
                call.addArgument(val.clone());
            } else {
                call.addArgument(Reflect.createLiteralExpression(Reflect.getDefault(param.getType().asString())));
            }
        });
    }

    private Expression determineParameterValue(Optional<StatementSequence> matchingSeq, ParameterSpec param, CallableDeclaration<?> childMethod) {
        Expression val = null;
        if (matchingSeq.isPresent() && param.getStartLine() != null && param.getStartColumn() != null) {
            StatementSequence primary = cluster.primary();
            int lineOffset = param.getStartLine() - primary.range().startLine();
            int colOffset = param.getStartColumn() - (lineOffset == 0 ? primary.range().startColumn() : 1);

            int targetLine = matchingSeq.get().range().startLine() + lineOffset;
            int targetCol = (lineOffset == 0 ? matchingSeq.get().range().startColumn() : 1) + colOffset;

            val = findNodeByCoordinates(matchingSeq.get(), targetLine, targetCol);
        }

        if (val == null) {
            val = findLiteralForParameter(childMethod, param);
        }
        return val;
    }

    private void computeParamNameOverrides(CallableDeclaration<?> method) {
        Set<String> declaredVars = new HashSet<>();
        getCallableBody(method).ifPresent(body -> body.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .forEach(v -> declaredVars.add(v.getNameAsString())));
        
        this.paramNameOverrides = MethodExtractor.computeParamNameOverridesStatic(
                declaredVars, recommendation.getSuggestedParameters());
    }

    private Optional<BlockStmt> getCallableBody(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration m) {
            return m.getBody();
        } else if (callable instanceof ConstructorDeclaration c) {
            return Optional.of(c.getBody());
        }
        return Optional.empty();
    }

    private Expression findLiteralForParameter(CallableDeclaration<?> method, com.raditha.dedup.model.ParameterSpec param) {
        // Scan method body for literals of the required type
        // This is tricky. For ReportGenerator, it's usually strings.
        for (com.github.javaparser.ast.expr.LiteralExpr literal : method
                .findAll(com.github.javaparser.ast.expr.LiteralExpr.class)) {
            // Check type compatibility
            if (param.getType().asString().contains("String") && literal.isStringLiteralExpr()) {
                // We should ensure we haven't already used this literal, but for now...
                return literal.clone();
            }
            if (param.getType().asString().contains("int") && literal.isIntegerLiteralExpr()) {
                return literal.clone();
            }
        }
        return null;
    }

    private void addFunctionalArguments(MethodCallExpr call, CallableDeclaration<?> childMethod) {
        if (!functionalPredicate.enabled) {
            return;
        }
        MethodCallExpr candidate = findPredicateMethodCall(childMethod, functionalPredicate.scopeName);
        String methodName = (candidate != null) ? candidate.getNameAsString() : resolvePredicateMethodName(childMethod);

        if (methodName == null) {
            return;
        }
        String lambdaParam = "item";
        String lambdaSource = lambdaParam + " -> " + lambdaParam + "." + methodName + "()";
        call.addArgument(com.github.javaparser.StaticJavaParser.parseExpression(lambdaSource));
    }

    private boolean methodExists(ClassOrInterfaceDeclaration clazz, MethodDeclaration method) {
        return clazz.getMethods().stream()
                .anyMatch(m -> signaturesMatch(m, method));
    }

    private FunctionalPredicateInfo buildFunctionalPredicateInfo() {
        if (recommendation.getVariationAnalysis() == null) {
            return FunctionalPredicateInfo.disabled();
        }
        List<com.raditha.dedup.model.VaryingExpression> variations =
                recommendation.getVariationAnalysis().getVaryingExpressions();
        com.raditha.dedup.model.VaryingExpression variation = null;
        for (com.raditha.dedup.model.VaryingExpression candidate : variations) {
            if (!(candidate.expr1() instanceof MethodCallExpr mc1)
                    || !(candidate.expr2() instanceof MethodCallExpr mc2)) {
                continue;
            }
            if (!mc1.getArguments().isEmpty() || !mc2.getArguments().isEmpty()) {
                continue;
            }
            if (candidate.type() != null
                    && (!candidate.type().isPrimitive() || !"boolean".equals(candidate.type().describe()))) {
                continue;
            }
            if (mc1.getScope().isEmpty()) {
                continue;
            }
            variation = candidate;
            break;
        }
        if (variation == null) {
            return FunctionalPredicateInfo.disabled();
        }

        MethodCallExpr m1 = (MethodCallExpr) variation.expr1();
        String scopeName = m1.getScope().get().toString();
        Set<String> methodNames = new HashSet<>();
        for (MethodDeclaration method : cluster.getContainingMethods()) {
            MethodCallExpr call = findPredicateMethodCall(method, scopeName);
            if (call == null) {
                return FunctionalPredicateInfo.disabled();
            }
            methodNames.add(call.getNameAsString());
        }

        String paramType = "Predicate<" + resolveScopeType(scopeName, cluster.primary().containingCallable()) + ">";
        String parentName = computeFunctionalParentName();

        return new FunctionalPredicateInfo(true, methodNames, scopeName, "filter", paramType, parentName);
    }

    private MethodCallExpr findPredicateMethodCall(CallableDeclaration<?> method, String scopeName) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                continue;
            }
            if (scopeName != null && !scopeName.equals(call.getScope().get().toString())) {
                continue;
            }
            return call;
        }
        return null;
    }

    private String resolvePredicateMethodName(CallableDeclaration<?> method) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (functionalPredicate.methodNames.contains(call.getNameAsString())) {
                return call.getNameAsString();
            }
        }
        return functionalPredicate.methodNames.stream().findFirst().orElse(null);
    }

    private String resolveScopeType(String scopeName, CallableDeclaration<?> method) {
        for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
            if (param.getNameAsString().equals(scopeName)) {
                return param.getType().asString();
            }
        }
        for (com.github.javaparser.ast.body.VariableDeclarator vd : method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (vd.getNameAsString().equals(scopeName)) {
                return vd.getType().asString();
            }
        }
        for (com.github.javaparser.ast.stmt.ForEachStmt foreach : method.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class)) {
            if (foreach.getVariable().getVariable(0).getNameAsString().equals(scopeName)) {
                return foreach.getVariable().getElementType().asString();
            }
        }
        return "Object";
    }

    private String computeFunctionalParentName() {
        Set<String> names = new HashSet<>();
        for (MethodDeclaration method : cluster.getContainingMethods()) {
            names.add(method.getNameAsString());
        }
        if (names.isEmpty()) {
            return fallbackFunctionalName();
        }
        String prefix = findCommonPrefix(names);
        String suffix = findCommonSuffix(names);
        int minLen = names.stream().mapToInt(String::length).min().orElse(0);
        if (prefix.length() >= 3 && suffix.length() >= 3 && prefix.length() + suffix.length() <= minLen) {
            return prefix + suffix;
        }
        return fallbackFunctionalName();
    }

    private String fallbackFunctionalName() {
        String suggested = recommendation.getSuggestedMethodName();
        if (suggested != null && !suggested.isEmpty()) {
            return suggested;
        }
        CallableDeclaration<?> method = cluster.primary().containingCallable();
        return method != null ? method.getNameAsString() : "extractedMethod";
    }

    private String findCommonPrefix(Set<String> names) {
        Iterator<String> iterator = names.iterator();
        String reference = iterator.next();
        int end = reference.length();
        for (String name : names) {
            int i = 0;
            while (i < end && i < name.length() && reference.charAt(i) == name.charAt(i)) {
                i++;
            }
            end = Math.min(end, i);
        }
        return reference.substring(0, end);
    }

    private record FunctionalPredicateInfo(
            boolean enabled,
            Set<String> methodNames,
            String scopeName,
            String parameterName,
            String parameterType,
            String parentMethodName) {
        static FunctionalPredicateInfo disabled() {
            return new FunctionalPredicateInfo(false, Set.of(), "", "", "", "");
        }
    }
    private void configureMethodModifiers(CallableDeclaration<?> originalMethod, MethodDeclaration newMethod) {
        newMethod.getModifiers().clear();
        if (originalMethod.isPublic()) {
            newMethod.setModifiers(Modifier.Keyword.PUBLIC);
        } else {
            newMethod.setModifiers(Modifier.Keyword.PROTECTED);
        }
        if (originalMethod.isStatic()) {
            newMethod.addModifier(Modifier.Keyword.STATIC);
        }
    }

    private void addSuggestedParameters(MethodDeclaration newMethod, Map<ParameterSpec, String> paramNameOverrides) {
        recommendation.getSuggestedParameters().forEach(p -> {
            String targetName = paramNameOverrides.getOrDefault(p, p.getName());
            if (!fieldsToExtract.containsKey(targetName)) {
                boolean exists = newMethod.getParameters().stream()
                        .anyMatch(param -> param.getNameAsString().equals(targetName));
                if (!exists) {
                    newMethod.addParameter(p.getType(), targetName);
                }
            }
        });
    }
}
