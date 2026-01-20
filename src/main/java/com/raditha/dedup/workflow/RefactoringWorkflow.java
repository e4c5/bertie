package com.raditha.dedup.workflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.refactoring.RefactoringEngine.RefactoringSession;

import java.io.IOException;
import java.util.List;

/**
 * Strategy interface for executing a refactoring workflow on a specific class.
 */
public interface RefactoringWorkflow {

    /**
     * Execute the refactoring workflow.
     *
     * @param clazz           The class to refactor
     * @param initialClusters The initial set of duplicate clusters found in this class
     * @param cu              The compilation unit containing the class (may be modified in-place)
     * @return The results of the refactoring session
     */
    RefactoringSession execute(ClassOrInterfaceDeclaration clazz, 
                               List<DuplicateCluster> initialClusters, 
                               CompilationUnit cu) throws IOException, InterruptedException;
}
