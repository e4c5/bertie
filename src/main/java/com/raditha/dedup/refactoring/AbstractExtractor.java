package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.depsolver.DependencyAnalyzer;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;

import java.nio.file.Path;
import java.util.*;


/**
 * Abstract base class for refactorers that extract duplicate code into new classes and methods
 *
 * Provides common functionality for both utility class and parent class
 * extraction.
 */
public abstract class AbstractExtractor {

    protected DuplicateCluster cluster;
    protected RefactoringRecommendation recommendation;
    protected Map<Path, String> modifiedFiles;
    protected Set<CompilationUnit> involvedCus;
    protected Map<CompilationUnit, Path> cuToPath;
    protected Map<CompilationUnit, List<CallableDeclaration<?>>> methodsToRemove;
    protected String packageName;

    /**
     * Apply the refactoring to extract code into a new class.
     */
    public abstract MethodExtractor.RefactoringResult refactor(
            DuplicateCluster cluster, RefactoringRecommendation recommendation);

    /**
     * Initialize common fields for refactoring.
     */
    protected void initialize(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        this.cluster = cluster;
        this.recommendation = recommendation;
        this.modifiedFiles = new LinkedHashMap<>();

        StatementSequence primary = cluster.primary();
        CompilationUnit primaryCu = primary.compilationUnit();
        this.packageName = getPackageName(primaryCu);

        this.involvedCus = collectInvolvedCUs();
        this.cuToPath = buildCUToPathMap();
        this.methodsToRemove = buildMethodsToRemoveMap();
    }

    /**
     * Collect all CompilationUnits involved in the cluster.
     */
    protected Set<CompilationUnit> collectInvolvedCUs() {
        Set<CompilationUnit> involved = Collections.newSetFromMap(new IdentityHashMap<>());
        involved.add(cluster.primary().compilationUnit());
        cluster.duplicates().forEach(pair -> {
            involved.add(pair.seq1().compilationUnit());
            involved.add(pair.seq2().compilationUnit());
        });
        return involved;
    }

    /**
     * Build a mapping from CompilationUnit to its source file Path.
     */
    protected Map<CompilationUnit, Path> buildCUToPathMap() {
        Map<CompilationUnit, Path> map = new IdentityHashMap<>();
        map.put(cluster.primary().compilationUnit(), cluster.primary().sourceFilePath());
        cluster.duplicates().forEach(pair -> {
            map.put(pair.seq1().compilationUnit(), pair.seq1().sourceFilePath());
            map.put(pair.seq2().compilationUnit(), pair.seq2().sourceFilePath());
        });
        return map;
    }

    /**
     * Build a mapping from CompilationUnit to the method that should be removed.
     */
    protected Map<CompilationUnit, List<CallableDeclaration<?>>> buildMethodsToRemoveMap() {
        Map<CompilationUnit, List<CallableDeclaration<?>>> map = new IdentityHashMap<>();
        
        java.util.function.BiConsumer<CompilationUnit, CallableDeclaration<?>> add = (cu, method) -> {
            if (cu != null && method != null) {
                map.computeIfAbsent(cu, k -> new ArrayList<>()).add(method);
            }
        };

        StatementSequence primary = cluster.primary();
        add.accept(primary.compilationUnit(), primary.containingCallable());

        cluster.duplicates().forEach(pair -> {
            add.accept(pair.seq1().compilationUnit(), pair.seq1().containingCallable());
            add.accept(pair.seq2().compilationUnit(), pair.seq2().containingCallable());
        });
        return map;
    }

    /**
     * Collect the set of import names needed for a method.
     * Uses DependencyAnalyzer to identify only the imports actually required.
     * 
     * @param method The method to analyze
     * @return Set of import names (fully qualified or simple names) needed by the method
     */
    protected Set<String> collectRequiredImports(CallableDeclaration<?> method) {
        Set<String> requiredImports = new HashSet<>();
    
        // Create a dependency analyzer to collect imports for this method
        DependencyAnalyzer analyzer = new DependencyAnalyzer() {
            @Override
            protected void onImportDiscovered(GraphNode node, ImportWrapper imp) {
                // Collect the import name when discovered during dependency analysis
                if (imp != null && imp.getImport() != null) {
                    requiredImports.add(imp.getImport().getNameAsString());
                }
            }
        };
        
        // Analyze dependencies for the method
        if (method instanceof MethodDeclaration md) {
            analyzer.collectDependencies(Collections.singleton(md));
        }

        return requiredImports;
    }

    /**
     * Check if an import is needed by a method.
     * Uses the collected required imports from dependency analysis.
     * 
     * @param imp The import declaration to check
     * @param requiredImportNames Set of import names that are required
     * @return true if the import is needed, false otherwise
     */
    protected boolean isImportNeeded(ImportDeclaration imp, Set<String> requiredImportNames,
            CallableDeclaration<?> method) {
        if (requiredImportNames.isEmpty()) {
            // Fallback: if analysis failed, copy all imports to be safe
            return true;
        }
        
        String importName = imp.getNameAsString();
        String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
        
        // Check exact match
        if (requiredImportNames.contains(importName)) {
            return true;
        }
        
        // For wildcard imports, check if any required import starts with this package
        if (imp.isAsterisk()) {

            return requiredImportNames.stream()
                    .anyMatch(required -> required.startsWith(importName + "."));
        }
        
        // For static imports, check both the full path and the simple name
        if (imp.isStatic()) {
            return requiredImportNames.contains(simpleName) || 
                   requiredImportNames.stream().anyMatch(req -> req.endsWith("." + simpleName));
        }
        return methodUsesType(method, simpleName);
    }

    /**
     * Get package name from a CompilationUnit.
     */
    protected String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");
    }

    /**
     * Copy necessary imports from source CU to target CU for a given method.
     * Uses DependencyAnalyzer to intelligently identify only required imports.
     */
    protected void copyNeededImports(CompilationUnit sourceCu, CompilationUnit targetCu,
            CallableDeclaration<?> method) {
        
        // Collect the set of imports required by this method using dependency analysis
        Set<String> requiredImportNames = collectRequiredImports(method);
        
        // Get the target package to avoid importing classes from the same package
        String targetPackage = getPackageName(targetCu);
        
        // Filter and copy only the needed imports
        Set<ImportDeclaration> addedImports = new HashSet<>();
        for (ImportDeclaration imp : sourceCu.getImports()) {
            // Skip imports from the same package (not needed)
            String importName = imp.getNameAsString();
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String importPackage = importName.contains(".")
                        ? importName.substring(0, importName.lastIndexOf('.'))
                        : "";
                if (importPackage.equals(targetPackage)) {
                    continue;
                }
            }
            
            // Check if this import is needed
            if (isImportNeeded(imp, requiredImportNames, method)) {
                // Avoid duplicate imports
                boolean isDuplicate = addedImports.stream()
                        .anyMatch(existing -> existing.getNameAsString().equals(importName));
                if (!isDuplicate) {
                    ImportDeclaration clonedImport = imp.clone();
                    targetCu.addImport(clonedImport);
                    addedImports.add(clonedImport);
                }
            }
        }
    }

    private boolean methodUsesType(CallableDeclaration<?> method, String simpleName) {
        return method.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).stream()
                .anyMatch(t -> t.getNameAsString().equals(simpleName));
    }

    /**
     * Find the primary class declaration in a CompilationUnit.
     */
    protected Optional<ClassOrInterfaceDeclaration> findPrimaryClass(CompilationUnit cu) {
        return cu.findFirst(ClassOrInterfaceDeclaration.class);
    }

    protected Expression findNodeByCoordinates(StatementSequence sequence, int line, int column) {
        for (Statement stmt : sequence.statements()) {
            for (Expression expr : stmt.findAll(Expression.class)) {
                if (expr.getRange().isPresent()) {
                    com.github.javaparser.Position begin = expr.getRange().get().begin;
                    if (begin.line == line && begin.column == column) {
                        return expr;
                    }
                }
            }
        }
        return null;
    }
}
