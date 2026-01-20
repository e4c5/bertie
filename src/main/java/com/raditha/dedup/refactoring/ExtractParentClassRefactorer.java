package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;

import java.nio.file.Path;
import java.util.*;

/**
 * Refactorer that extracts cross-class duplicate methods into a common parent class.
 * Child classes are modified to extend the new parent, inheriting the shared method.
 */
public class ExtractParentClassRefactorer extends AbstractClassExtractorRefactorer {

    @Override
    public ExtractMethodRefactorer.RefactoringResult refactor(
            DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        
        StatementSequence primary = cluster.primary();
        CompilationUnit primaryCu = primary.compilationUnit();
        Path sourceFile = primary.sourceFilePath();
        MethodDeclaration methodToExtract = primary.containingMethod();
        
        // Validate we have a containing method and class
        if (methodToExtract == null) {
            throw new IllegalArgumentException("No containing method found");
        }
        
        Optional<ClassOrInterfaceDeclaration> primaryClass = findPrimaryClass(primaryCu);
        if (primaryClass.isEmpty()) {
            throw new IllegalArgumentException("No containing class found");
        }
        
        String packageName = getPackageName(primaryCu);
        String parentClassName = determineParentClassName(cluster);

        // Safety Check: Cannot extract method if it uses inner classes/interfaces defined in the primary CU
        // because the new parent class won't have access to them (visibility/scope issues).
        validateNoInnerClassUsage(primaryCu, methodToExtract);

        // Safety Check: Cannot extract method if it uses instance fields of the class
        // (unless we extract fields too, which we don't yet).
        validateNoFieldUsage(primaryCu, methodToExtract);
        
        // Check if classes already share a common parent
        Optional<ClassOrInterfaceDeclaration> existingParent = findExistingCommonParent(cluster);
        
        // Check if one of the involved classes IS the intended parent (Peer-as-Parent)
        Optional<CompilationUnit> peerParentCu = findPeerParent(cluster, parentClassName);
        boolean isPeerParent = peerParentCu.isPresent();

        Map<Path, String> modifiedFiles = new LinkedHashMap<>();
        
        if (existingParent.isPresent()) {
            // Add method to existing parent class (external parent)
            addMethodToExistingParent(existingParent.get(), methodToExtract, modifiedFiles, cluster);
        } else if (isPeerParent) {
             // One of the peers is promoting to be the parent
             // We don't create a new class, but we need to ensure the method in it is preserved and correct
             // The modification will happen when we process the involved CUs loop
             CompilationUnit parentCu = peerParentCu.get();
             // Ensure parent method is protected or public (not private) if it was private
             // Logic handled below or implicitly by not removing it
        } else {
            // Create new parent class
            CompilationUnit parentCu = createParentClass(parentClassName, packageName, methodToExtract);
            Path parentPath = sourceFile.getParent().resolve(parentClassName + ".java");
            modifiedFiles.put(parentPath, parentCu.toString());
        }
        
        // Use inherited methods from base class
        Set<CompilationUnit> involvedCus = collectInvolvedCUs(cluster);
        Map<CompilationUnit, Path> cuToPath = buildCUToPathMap(cluster);
        Map<CompilationUnit, MethodDeclaration> methodsToRemove = buildMethodsToRemoveMap(cluster);
        
        // Modify each child class
        for (CompilationUnit currentCu : involvedCus) {
            boolean isCurrentCuParent = isPeerParent && currentCu == peerParentCu.get();

            // Add extends clause if parent was newly created OR if we are a peer extending another peer
            // Do NOT add extends clause if we ARE the parent
            // Add extends clause if parent was newly created OR if we are a peer extending another peer
            // Do NOT add extends clause if we ARE the parent
            if (existingParent.isEmpty() && !isCurrentCuParent) {
                addExtendsClause(currentCu, parentClassName);

                // Add import if parent class is in a different package
                String currentPackage = getPackageName(currentCu);
                if (!currentPackage.equals(packageName)) {
                    if (packageName.isEmpty()) {
                        // Parent in default package, child in named package -> Cannot import!
                        // This is a Java limitation. But usually we scan src/main/java and have packages.
                        // If this happens, we should probably abort or warn?
                        // For now, assume packages exist.
                        System.err.println("Warning: Parent class in default package cannot be imported by " + currentPackage);
                    } else {
                        currentCu.addImport(packageName + "." + parentClassName);
                    }
                }
            }
            
            // Remove the duplicate method (now inherited from parent)
            // Do NOT remove if we ARE the parent (it becomes the base method)
            MethodDeclaration methodToRemove = methodsToRemove.get(currentCu);
            if (methodToRemove != null) {
                if (isCurrentCuParent) {
                     // Ensure visibility is at least protected
                     if (methodToRemove.isPrivate()) {
                         methodToRemove.setModifiers(Modifier.Keyword.PROTECTED);
                     }
                } else {
                    // Safety Check: EXTRACT_PARENT_CLASS does not yet support parameterization.
                    // If the method body differs from the parent method (e.g. different literals),
                    // replacing it with a call to parent will lose behavior (Regression: Alice -> Start User).
                    if (!areMethodsEquivalent(methodToExtract, methodToRemove)) {
                         throw new IllegalStateException("Skipped: Method body in " + 
                                 currentCu.getPrimaryTypeName().orElse("unknown") + 
                                 " differs from parent (literals/logic mismatch). Parameterization strategy required.");
                    }

                    // Check for invalidation/delegation
                    String parentMethodName = methodToExtract.getNameAsString();
                    String childMethodName = methodToRemove.getNameAsString();
                    
                    if (childMethodName.equals(parentMethodName)) {
                        methodToRemove.remove();
                    } else {
                        // Delegate to the parent method if names differ (preserve API)
                        BlockStmt body = new BlockStmt();
                        MethodCallExpr call = new MethodCallExpr(parentMethodName);
                        methodToRemove.getParameters().forEach(p -> call.addArgument(p.getNameAsExpression()));
                        
                        if (methodToRemove.getType().isVoidType()) {
                            body.addStatement(call);
                        } else {
                            body.addStatement(new ReturnStmt(call));
                        }
                        methodToRemove.setBody(body);
                    }
                }
            }
            
            modifiedFiles.put(cuToPath.get(currentCu), currentCu.toString());
        }
        
        return new ExtractMethodRefactorer.RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Extracted to parent class: " + parentClassName);
    }

    private Optional<CompilationUnit> findPeerParent(DuplicateCluster cluster, String parentClassName) {
         for (StatementSequence seq : cluster.allSequences()) {
            Optional<ClassOrInterfaceDeclaration> classDecl = findPrimaryClass(seq.compilationUnit());
            if (classDecl.isPresent() && classDecl.get().getNameAsString().equals(parentClassName)) {
                return Optional.of(seq.compilationUnit());
            }
        }
        return Optional.empty();
    }
    
    /**
     * Create a new abstract parent class with the extracted method.
     */
    private CompilationUnit createParentClass(String className, String packageName, 
            MethodDeclaration originalMethod) {
        CompilationUnit parentCu = new CompilationUnit();
        if (!packageName.isEmpty()) {
            parentCu.setPackageDeclaration(packageName);
        }
        
        // Clone method and make protected
        MethodDeclaration newMethod = originalMethod.clone();
        newMethod.setModifiers(Modifier.Keyword.PROTECTED);
        
        // Create abstract class
        ClassOrInterfaceDeclaration parentClass = parentCu.addClass(className)
                .setPublic(true) // Ensure public visibility for cross-package access
                .setAbstract(true)
                .setJavadocComment("Common parent class for shared functionality.");
        
        parentClass.addMember(newMethod);
        
        // Copy needed imports
        CompilationUnit originalCu = originalMethod.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("Original method not part of a CompilationUnit"));
        copyNeededImports(originalCu, parentCu, newMethod);
        
        return parentCu;
    }
    
    /**
     * Add extends clause to a class.
     */
    private void addExtendsClause(CompilationUnit cu, String parentClassName) {
        findPrimaryClass(cu).ifPresent(classDecl -> {
            if (classDecl.getExtendedTypes().isEmpty()) {
                classDecl.addExtendedType(new ClassOrInterfaceType(null, parentClassName));
            }
        });
    }
    
    /**
     * Check if all classes in the cluster already share a common parent.
     */
    private Optional<ClassOrInterfaceDeclaration> findExistingCommonParent(DuplicateCluster cluster) {
        Set<String> parentNames = new HashSet<>();
        
        for (StatementSequence seq : cluster.allSequences()) {
            Optional<ClassOrInterfaceDeclaration> classDecl = findPrimaryClass(seq.compilationUnit());
            if (classDecl.isPresent()) {
                var extendedTypes = classDecl.get().getExtendedTypes();
                if (!extendedTypes.isEmpty()) {
                    parentNames.add(extendedTypes.get(0).getNameAsString());
                } else {
                    // At least one class has no parent - can't use existing parent
                    return Optional.empty();
                }
            }
        }
        
        // If all classes extend the same parent, we could potentially add to it
        // For now, return empty to keep implementation simple - always create new parent
        // TODO: Implement finding and modifying existing parent class
        return Optional.empty();
    }
    
    /**
     * Add method to an existing common parent class.
     */
    private void addMethodToExistingParent(ClassOrInterfaceDeclaration parentClass,
            MethodDeclaration method, Map<Path, String> modifiedFiles, DuplicateCluster cluster) {
        // Clone and add the method
        MethodDeclaration newMethod = method.clone();
        newMethod.setModifiers(Modifier.Keyword.PROTECTED);
        parentClass.addMember(newMethod);
        
        // Get the parent's CU and path
        CompilationUnit parentCu = parentClass.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("Parent class not part of a CompilationUnit"));
        Path parentPath = cluster.primary().sourceFilePath().getParent()
                .resolve(parentClass.getNameAsString() + ".java");
        modifiedFiles.put(parentPath, parentCu.toString());
    }
    
    /**
     * Determine a suitable name for the parent class.
     */
    private String determineParentClassName(DuplicateCluster cluster) {
        // Try to find common substring in class names
        // Use TreeSet for deterministic ordering
        Set<String> classNames = new java.util.TreeSet<>();
        for (StatementSequence seq : cluster.allSequences()) {
            findPrimaryClass(seq.compilationUnit())
                    .ifPresent(c -> classNames.add(c.getNameAsString()));
        }
        
        // Handle empty case
        if (classNames.isEmpty()) {
            return "BaseClass";
        }
        
        // Find common suffix (e.g., "InventoryService", "ShippingService" -> "Service")
        String commonSuffix = findCommonSuffix(classNames);
        if (!commonSuffix.isEmpty() && commonSuffix.length() > 3) {
            return "Base" + commonSuffix;
        }
        
        // Fallback: use first class name with "Abstract" prefix
        String firstName = classNames.iterator().next();
        return "Abstract" + firstName;
    }
    
    /**
     * Find common suffix among class names.
     */
    private String findCommonSuffix(Set<String> names) {
        if (names.isEmpty()) return "";
        
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

    /**
     * Check if method uses any inner classes defined in the CU.
     * Throws IllegalStateException to abort refactoring if found.
     */
    private void validateNoInnerClassUsage(CompilationUnit cu, MethodDeclaration method) {
        List<String> innerClassNames = new ArrayList<>();
        
        // Find all nested types
        cu.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
            .filter(t -> t.isNestedType())
            .forEach(t -> innerClassNames.add(t.getNameAsString()));

        if (innerClassNames.isEmpty()) {
            return;
        }

        // Check for usage in method (Types, Constructors)
        // Check ClassOrInterfaceType
        for (ClassOrInterfaceType type : method.findAll(ClassOrInterfaceType.class)) {
            if (innerClassNames.contains(type.getNameAsString())) {
                 throw new IllegalStateException("Skipped: Method uses inner class '" + type.getNameAsString() + "' which cannot be extracted to parent class.");
            }
        }
    }

    /**
     * Check if method uses any instance fields of the class.
     * Throws IllegalStateException to abort refactoring if found.
     */
    private void validateNoFieldUsage(CompilationUnit cu, MethodDeclaration method) {
        // Collect field names
        Set<String> fieldNames = new HashSet<>();
        findPrimaryClass(cu).ifPresent(c -> 
            c.getFields().forEach(f -> 
                f.getVariables().forEach(v -> fieldNames.add(v.getNameAsString()))
            )
        );

        if (fieldNames.isEmpty()) return;

        // Check for usage
        method.findAll(NameExpr.class).forEach(name -> {
            String identifier = name.getNameAsString();
            if (fieldNames.contains(identifier)) {
                // Check if shadowed by local variable or parameter
                if (!isShadowed(name, method)) {
                    throw new IllegalStateException("Skipped: Method uses field '" + identifier + "' which is not extracted.");
                }
            }
        });
    }

    /**
     * Check if a name expression is shadowed by a local variable or parameter.
     */
    private boolean isShadowed(NameExpr name, MethodDeclaration method) {
        String identifier = name.getNameAsString();
        
        // Check parameters
        boolean isParam = method.getParameters().stream()
                .anyMatch(p -> p.getNameAsString().equals(identifier));
        if (isParam) return true;

        // Check local variables (simplistic check: declared before usage?)
        // AST traversal to find ancestor blocks and declarations
        // This is complex. For now, assume if variable with same name declared in method, it shadows.
        // False positives (usage before decl) are safer (will skip refactoring) than false negatives.
        boolean isLocal = method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .anyMatch(v -> v.getNameAsString().equals(identifier));
        
        return isLocal;
    }

    /**
     * Checks if two methods are semantically equivalent for the purpose of replacement.
     * Since we don't parameterize in EXTRACT_PARENT_CLASS, they must be identical
     * (ignoring whitespace and comments) to be safe.
     */
    private boolean areMethodsEquivalent(MethodDeclaration m1, MethodDeclaration m2) {
        if (!m1.getBody().isPresent() || !m2.getBody().isPresent()) {
            return false;
        }
        
        // Normalize strings by removing all whitespace
        // This is a robust way to ignore formatting differences while catching literal differences
        String body1 = m1.getBody().get().toString().replaceAll("\\s+", "");
        String body2 = m2.getBody().get().toString().replaceAll("\\s+", "");
        
        return body1.equals(body2);
    }
}
