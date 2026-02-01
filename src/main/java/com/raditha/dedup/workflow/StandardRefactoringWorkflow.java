package com.raditha.dedup.workflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.RefactoringEngine.RefactoringSession;

import java.io.IOException;
import java.util.List;

/**
 * Standard refactoring workflow for regular (non-test) classes.
 * Performs a single pass of refactoring.
 */
public class StandardRefactoringWorkflow implements RefactoringWorkflow {

    private final RefactoringEngine engine;

    /**
     * Creates a new standard refactoring workflow.
     *
     * @param engine the refactoring engine to use
     */
    public StandardRefactoringWorkflow(RefactoringEngine engine) {
        this.engine = engine;
    }

    /**
     * Executes the standard refactoring workflow.
     *
     * @param clazz           the class to refactor
     * @param initialClusters the clusters of duplicates found in the class
     * @param cu              the compilation unit containing the class
     * @return a session tracking the refactoring results
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if the process is interrupted
     */
    @Override
    public RefactoringSession execute(ClassOrInterfaceDeclaration clazz,
                                      List<DuplicateCluster> initialClusters,
                                      CompilationUnit cu) throws IOException, InterruptedException {
        // Standard workflow simply delegates to the engine for a single pass
        // The engine processes the list of clusters provided
        return engine.processClusters(initialClusters);
    }
}
