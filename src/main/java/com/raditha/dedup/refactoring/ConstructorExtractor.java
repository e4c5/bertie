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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refactorer that eliminates constructor duplication using constructor delegation (this() calls).
 * 
 * Instead of extracting duplicate constructor code into a helper method (which fails when
 * final fields are assigned), this refactorer identifies a master constructor and delegates
 * to it from other constructors using this(...) calls.
 */
public class ConstructorExtractor extends AbstractExtractor {
    private static final Logger logger = LoggerFactory.getLogger(ConstructorExtractor.class);

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
            if (constructor == masterConstructor || refactoredConstructors.contains(constructor)) {
                continue;
            }

            // Replace the duplicate sequence with a this() call
            if (replaceWithDelegation(sequence, constructor, masterConstructor)) {
                refactoredConstructors.add(constructor);
            }
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
            // Default to primary if no perfect master found
            return (ConstructorDeclaration) cluster.primary().containingCallable();
        }

        // Prefer the one with the most parameters (standard Java delegation pattern)
        return constructors.stream()
                .max(Comparator.comparingInt(c -> c.getParameters().size()))
                .get();
    }

    /**
     * Replace the duplicate sequence in a constructor with a this() call to the master.
     * 
     * @return true if delegation was applied, false otherwise
     */
    private boolean replaceWithDelegation(StatementSequence sequence, 
                                      ConstructorDeclaration constructor,
                                      ConstructorDeclaration masterConstructor) {
        // Check if constructor already has a this() or super() call
        if (hasExplicitConstructorCall(constructor)) {
            logger.warn("Skipping constructor delegation: constructor in {} already has an explicit this()/super() call", 
                    sequence.sourceFilePath());
            return false;
        }

        // Check if the duplicate is at the start of the constructor
        if (sequence.startOffset() != 0) {
            logger.warn("Skipping constructor delegation: duplicate sequence in {} is not at the start of the constructor",
                    sequence.sourceFilePath());
            return false;
        }

        BlockStmt body = constructor.getBody();
        
        // Create the this() call with appropriate arguments
        ExplicitConstructorInvocationStmt thisCall = createThisCall(sequence, masterConstructor);
        
        if (thisCall == null) {
            logger.warn("Skipping constructor delegation: cannot safely map all parameters for master constructor in {}",
                    sequence.sourceFilePath());
            return false;
        }

        // Use the duplicate sequence length detected by the algorithm
        int duplicateCount = sequence.statements().size();
        
        // Remove the duplicate statements
        for (int i = 0; i < duplicateCount; i++) {
            if (!body.getStatements().isEmpty()) {
                body.getStatement(0).remove();
            }
        }

        // Insert the this() call at the beginning
        body.getStatements().add(0, thisCall);
        return true;
    }

    /**
     * Check if a constructor already has an explicit this() or super() call.
     */
    private boolean hasExplicitConstructorCall(ConstructorDeclaration constructor) {
        return constructor.getBody().getStatements().stream()
                .anyMatch(ExplicitConstructorInvocationStmt.class::isInstance);
    }

    /**
     * Create a this(...) call with appropriate arguments.
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
            
            // 1. Try exact name match
            Optional<com.github.javaparser.ast.body.Parameter> matchingParam = currentConstructor.getParameters().stream()
                    .filter(p -> p.getNameAsString().equals(paramName))
                    .findFirst();

            // 2. Fallback to type match if name not found
            if (matchingParam.isEmpty()) {
                matchingParam = currentConstructor.getParameters().stream()
                        .filter(p -> p.getType().equals(param.getType()))
                        .findFirst();
            }

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
