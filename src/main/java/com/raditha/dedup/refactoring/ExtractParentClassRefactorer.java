package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
        
        // Check if classes already share a common parent
        Optional<ClassOrInterfaceDeclaration> existingParent = findExistingCommonParent(cluster);
        
        Map<Path, String> modifiedFiles = new LinkedHashMap<>();
        
        if (existingParent.isPresent()) {
            // Add method to existing parent class
            addMethodToExistingParent(existingParent.get(), methodToExtract, modifiedFiles, cluster);
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
            // Add extends clause if parent was newly created
            if (existingParent.isEmpty()) {
                addExtendsClause(currentCu, parentClassName);
            }
            
            // Remove the duplicate method (now inherited from parent)
            MethodDeclaration methodToRemove = methodsToRemove.get(currentCu);
            if (methodToRemove != null) {
                methodToRemove.remove();
            }
            
            modifiedFiles.put(cuToPath.get(currentCu), currentCu.toString());
        }
        
        return new ExtractMethodRefactorer.RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Extracted to parent class: " + parentClassName);
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
}
