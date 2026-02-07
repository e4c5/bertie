package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;

import java.nio.file.Path;
import java.util.*;

/**
 * Refactorer that eliminates constructor duplication using constructor delegation (this() calls).
 * 
 * Instead of extracting duplicate constructor code into a helper method (which fails when
 * final fields are assigned), this refactorer identifies a master constructor and delegates
 * to it from other constructors using this(...) calls.
 */
public class ConstructorExtractr extends AbstractExtractor {
    /**
     * Refactor constructors to use delegation.
     *
     * @param cluster The duplicate cluster containing constructor duplicates
     * @param recommendation The refactoring recommendation
     * @return Result of the refactoring operation
     */
    public MethodExtractor.RefactoringResult refactor(DuplicateCluster cluster,
                                                      RefactoringRecommendation recommendation) {
        this.cluster = cluster;
        this.recommendation = recommendation;

        // Validate that all sequences are in constructors
        validateAllConstructors();

        // Select the master constructor (the one others will delegate to)
        ConstructorDeclaration masterConstructor = selectMasterConstructor();

        // Get the compilation unit
        CompilationUnit cu = cluster.primary().compilationUnit();
        if (cu == null) {
            throw new IllegalStateException("No compilation unit found for primary sequence");
        }

        // Track which constructors have been refactored
        Set<ConstructorDeclaration> refactoredConstructors = new HashSet<>();

        // Replace duplicates in other constructors with this() calls
        for (StatementSequence sequence : cluster.allSequences()) {
            ConstructorDeclaration constructor = (ConstructorDeclaration) sequence.containingCallable();
            
            // Skip the master constructor
            if (constructor == masterConstructor) {
                continue;
            }

            // Skip if already refactored
            if (refactoredConstructors.contains(constructor)) {
                continue;
            }

            // Replace the duplicate sequence with a this() call
            replaceWithDelegation(sequence, constructor, masterConstructor);
            refactoredConstructors.add(constructor);
        }

        // Return the refactored code
        Path sourceFile = cluster.primary().sourceFilePath();
        return new MethodExtractor.RefactoringResult(
                sourceFile,
                cu.toString(),
                recommendation.getStrategy(),
                "Applied constructor delegation using this() calls");
    }

    /**
     * Validate that all sequences in the cluster are in constructors.
     */
    private void validateAllConstructors() {
        for (StatementSequence sequence : cluster.allSequences()) {
            CallableDeclaration<?> callable = sequence.containingCallable();
            if (!(callable instanceof ConstructorDeclaration)) {
                throw new IllegalStateException(
                        "CONSTRUCTOR_DELEGATION strategy requires all sequences to be in constructors");
            }
        }
    }

    /**
     * Select the master constructor that others will delegate to.
     * 
     * Strategy:
     * 1. Prefer the no-arg constructor if it has the duplicate sequence
     * 2. Otherwise, prefer the constructor with the most parameters
     * 3. Otherwise, use the primary sequence's constructor
     */
    private ConstructorDeclaration selectMasterConstructor() {
        int duplicateCount = cluster.primary().statements().size();

        List<ConstructorDeclaration> constructors = new ArrayList<>();
        for (StatementSequence sequence : cluster.allSequences()) {
            ConstructorDeclaration cd = (ConstructorDeclaration) sequence.containingCallable();
            // A perfect master is one where the duplicate sequence is the ENTIRE body
            if (cd.getBody().getStatements().size() == duplicateCount) {
                constructors.add(cd);
            }
        }

        if (constructors.isEmpty()) {
            return null;
        }

        // Try to find no-arg constructor among perfect candidates
        ConstructorDeclaration noArgConstructor = constructors.stream()
                .filter(c -> c.getParameters().isEmpty())
                .findFirst()
                .orElse(null);

        if (noArgConstructor != null) {
            return noArgConstructor;
        }

        // Otherwise, select the one with the most parameters
        return constructors.stream()
                .max(Comparator.comparingInt(c -> c.getParameters().size()))
                .get();
    }

    /**
     * Replace the duplicate sequence in a constructor with a this() call to the master.
     */
    private void replaceWithDelegation(StatementSequence sequence, 
                                      ConstructorDeclaration constructor,
                                      ConstructorDeclaration masterConstructor) {
        // Check if constructor already has a this() or super() call
        if (hasExplicitConstructorCall(constructor)) {
            // Cannot add another this() call
            return;
        }

        // Check if the duplicate is at the start of the constructor
        if (sequence.startOffset() != 0) {
            // Can only delegate if duplicate is at the start
            return;
        }

        BlockStmt body = constructor.getBody();
        BlockStmt masterBody = masterConstructor.getBody();
        List<Statement> statements = body.getStatements();
        List<Statement> masterStatements = masterBody.getStatements();

        // Create the this() call with appropriate arguments
        ExplicitConstructorInvocationStmt thisCall = createThisCall(sequence, masterConstructor);
        
        if (thisCall == null) {
            // Cannot safely delegate due to unmappable parameters
            return;
        }

        // Use the duplicate sequence length detected by the algorithm
        // The sequence already represents what was identified as duplicate
        int duplicateCount = sequence.statements().size();
        
        // Remove the duplicate statements
        // Cache the size because it will shrink as we remove
        int statementsToRemove = Math.min(duplicateCount, statements.size());
        for (int i = 0; i < statementsToRemove; i++) {
            statements.get(0).remove(); // Always remove first since list shrinks
        }

        // Insert the this() call at the beginning
        body.getStatements().add(0, thisCall);
    }

    /**
     * Check if a constructor already has an explicit this() or super() call.
     */
    private boolean hasExplicitConstructorCall(ConstructorDeclaration constructor) {
        return constructor.getBody().getStatements().stream()
                .anyMatch(s -> s instanceof ExplicitConstructorInvocationStmt);
    }

    /**
     * Create a this(...) call with appropriate arguments.
     * 
     * For now, we'll create a simple this() call without arguments.
     * A more sophisticated implementation would analyze the parameters needed.
     */
    private ExplicitConstructorInvocationStmt createThisCall(StatementSequence sequence,
                                                            ConstructorDeclaration masterConstructor) {
        ExplicitConstructorInvocationStmt thisCall = new ExplicitConstructorInvocationStmt();
        thisCall.setThis(true); // this() not super()

        // Build arguments based on master constructor's parameters
        NodeList<Expression> arguments = new NodeList<>();
        
        // For each parameter in the master constructor, try to find the corresponding value
        for (com.github.javaparser.ast.body.Parameter param : masterConstructor.getParameters()) {
            String paramName = param.getNameAsString();
            
            // Try to find a matching parameter in the current constructor
            ConstructorDeclaration currentConstructor = (ConstructorDeclaration) sequence.containingCallable();
            Optional<com.github.javaparser.ast.body.Parameter> matchingParam = currentConstructor.getParameters().stream()
                    .filter(p -> p.getNameAsString().equals(paramName) || 
                                p.getType().equals(param.getType()))
                    .findFirst();

            if (matchingParam.isPresent()) {
                // Use the parameter from current constructor
                arguments.add(new NameExpr(matchingParam.get().getNameAsString()));
            } else {
                // Try to extract the value from the duplicate sequence
                Expression value = extractValueForParameter(sequence, param);
                if (value != null) {
                    arguments.add(value);
                } else {
                    // Fix: Reject delegation if we cannot satisfy a parameter
                    return null;
                }
            }
        }

        thisCall.setArguments(arguments);
        return thisCall;
    }

    /**
     * Extract the value assigned to a parameter from the duplicate sequence.
     * 
     * This looks for assignments to fields that match the parameter type/name.
     */
    private Expression extractValueForParameter(StatementSequence sequence, 
                                               com.github.javaparser.ast.body.Parameter param) {
        String paramName = param.getNameAsString();
        
        // Look through the statements for assignments
        for (Statement stmt : sequence.statements()) {
            List<com.github.javaparser.ast.expr.AssignExpr> assignments = 
                    stmt.findAll(com.github.javaparser.ast.expr.AssignExpr.class);
            
            for (com.github.javaparser.ast.expr.AssignExpr assign : assignments) {
                String target = assign.getTarget().toString();
                
                // Check if this assignment is to a field matching the parameter name
                if (target.equals("this." + paramName) || target.equals(paramName)) {
                    return assign.getValue().clone();
                }
            }
        }
        
        return null;
    }
}
