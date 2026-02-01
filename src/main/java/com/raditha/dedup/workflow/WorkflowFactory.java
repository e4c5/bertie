package com.raditha.dedup.workflow;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.TestClassHelper;

/**
 * Factory for creating the appropriate RefactoringWorkflow for a class.
 */
public class WorkflowFactory {

    private final DuplicationAnalyzer analyzer;
    private final RefactoringEngine engine;

    /**
     * Creates a new WorkflowFactory.
     *
     * @param analyzer the duplication analyzer to use in workflows
     * @param engine   the refactoring engine to use in workflows
     */
    public WorkflowFactory(DuplicationAnalyzer analyzer, RefactoringEngine engine) {
        this.analyzer = analyzer;
        this.engine = engine;
    }

    /**
     * Get the workflow strategy for the given class.
     */
    public RefactoringWorkflow getWorkflow(ClassOrInterfaceDeclaration clazz) {
        // Use existing helper to check for test annotations
        if (TestClassHelper.isTestClass(clazz)) {
            return new IterativeTestRefactoringWorkflow(analyzer, engine);
        } else {
            return new StandardRefactoringWorkflow(engine);
        }
    }
}
