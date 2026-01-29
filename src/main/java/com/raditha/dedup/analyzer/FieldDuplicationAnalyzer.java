package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.Type;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes classes to identify duplicate fields across multiple classes.
 * This enables detection of classes that share common fields and could benefit
 * from extracting those fields to a common parent class.
 */
public class FieldDuplicationAnalyzer {

    /**
     * Represents a field signature for comparison.
     */
    public record FieldSignature(String name, String type) {
        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    /**
     * Represents a group of classes that share duplicate fields.
     */
    public record FieldDuplicationGroup(
            Set<String> classNames,
            List<FieldSignature> duplicatedFields,
            Map<String, CompilationUnit> classToCompilationUnit
    ) {
        public int getDuplicateCount() {
            return classNames.size();
        }

        public int getFieldCount() {
            return duplicatedFields.size();
        }
    }

    /**
     * Analyze all compilation units to find groups of classes with duplicate fields.
     * 
     * @param allCUs Map of class name to CompilationUnit
     * @return List of field duplication groups
     */
    public List<FieldDuplicationGroup> analyzeFieldDuplication(Map<String, CompilationUnit> allCUs) {
        // Step 1: Extract field signatures for each class
        Map<String, List<FieldSignature>> classToFields = new HashMap<>();
        Map<String, CompilationUnit> classNameToCU = new HashMap<>();

        for (Map.Entry<String, CompilationUnit> entry : allCUs.entrySet()) {
            CompilationUnit cu = entry.getValue();
            
            // Find all classes in this compilation unit
            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
            
            for (ClassOrInterfaceDeclaration clazz : classes) {
                if (clazz.isInterface()) {
                    continue; // Skip interfaces
                }
                
                String className = getFullyQualifiedName(cu, clazz);
                List<FieldSignature> fields = extractFieldSignatures(clazz);
                
                if (!fields.isEmpty()) {
                    classToFields.put(className, fields);
                    classNameToCU.put(className, cu);
                }
            }
        }

        // Step 2: Find groups of classes with common fields
        return findDuplicationGroups(classToFields, classNameToCU);
    }

    /**
     * Extract field signatures from a class declaration.
     */
    private List<FieldSignature> extractFieldSignatures(ClassOrInterfaceDeclaration clazz) {
        List<FieldSignature> signatures = new ArrayList<>();
        
        for (FieldDeclaration field : clazz.getFields()) {
            // Skip static fields - they shouldn't be extracted to parent
            if (field.isStatic()) {
                continue;
            }
            
            Type fieldType = field.getCommonType();
            String typeString = fieldType.asString();
            
            for (VariableDeclarator variable : field.getVariables()) {
                signatures.add(new FieldSignature(variable.getNameAsString(), typeString));
            }
        }
        
        return signatures;
    }

    /**
     * Find groups of classes that share common fields.
     */
    private List<FieldDuplicationGroup> findDuplicationGroups(
            Map<String, List<FieldSignature>> classToFields,
            Map<String, CompilationUnit> classNameToCU) {
        
        List<FieldDuplicationGroup> groups = new ArrayList<>();
        Set<String> processedClasses = new HashSet<>();
        
        List<String> classNames = new ArrayList<>(classToFields.keySet());
        
        // Compare each pair of classes
        for (int i = 0; i < classNames.size(); i++) {
            String class1 = classNames.get(i);
            
            if (processedClasses.contains(class1)) {
                continue;
            }
            
            List<FieldSignature> fields1 = classToFields.get(class1);
            Set<String> groupClasses = new HashSet<>();
            groupClasses.add(class1);
            
            // Find all classes that share fields with class1
            for (int j = i + 1; j < classNames.size(); j++) {
                String class2 = classNames.get(j);
                
                if (processedClasses.contains(class2)) {
                    continue;
                }
                
                List<FieldSignature> fields2 = classToFields.get(class2);
                List<FieldSignature> commonFields = findCommonFields(fields1, fields2);
                
                // Only consider significant duplication (at least 2 fields)
                if (commonFields.size() >= 2) {
                    groupClasses.add(class2);
                }
            }
            
            // If we found a group with duplicates, create a duplication group
            if (groupClasses.size() >= 2) {
                // Find fields common to ALL classes in the group
                List<FieldSignature> commonToAll = findFieldsCommonToAll(
                        groupClasses, classToFields);
                
                if (!commonToAll.isEmpty()) {
                    Map<String, CompilationUnit> groupCUs = groupClasses.stream()
                            .collect(Collectors.toMap(
                                    className -> className,
                                    classNameToCU::get
                            ));
                    
                    groups.add(new FieldDuplicationGroup(groupClasses, commonToAll, groupCUs));
                    processedClasses.addAll(groupClasses);
                }
            }
        }
        
        return groups;
    }

    /**
     * Find fields that are common to all classes in a group.
     */
    private List<FieldSignature> findFieldsCommonToAll(
            Set<String> classNames,
            Map<String, List<FieldSignature>> classToFields) {
        
        if (classNames.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Start with fields from first class
        Iterator<String> iterator = classNames.iterator();
        List<FieldSignature> commonFields = new ArrayList<>(classToFields.get(iterator.next()));
        
        // Intersect with fields from remaining classes
        while (iterator.hasNext()) {
            List<FieldSignature> nextFields = classToFields.get(iterator.next());
            commonFields.retainAll(nextFields);
        }
        
        return commonFields;
    }

    /**
     * Find fields that are common between two field lists.
     */
    private List<FieldSignature> findCommonFields(
            List<FieldSignature> fields1,
            List<FieldSignature> fields2) {
        
        return fields1.stream()
                .filter(fields2::contains)
                .collect(Collectors.toList());
    }

    /**
     * Get fully qualified class name.
     */
    private String getFullyQualifiedName(CompilationUnit cu, ClassOrInterfaceDeclaration clazz) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        
        String className = clazz.getNameAsString();
        
        if (packageName.isEmpty()) {
            return className;
        }
        
        return packageName + "." + className;
    }
}
