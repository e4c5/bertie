package com.raditha.dedup.extraction;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithBody;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.raditha.dedup.analysis.BoundaryRefiner;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts statement sequences from Java source files using sliding window.
 * Focuses on method and constructor bodies to find potential code duplicates.
 */
public class StatementExtractor {
    
    private final int minStatements;
    private final int maxWindowGrowth;
    private final boolean maximalOnly;
    
    /**
     * Create extractor with default minimum statement count (5) and window growth (5).
     */
    public StatementExtractor() {
        this(5, 5, true);
    }
    
    /**
     * Create extractor with custom minimum statement count and default window growth.
     * 
     * @param minStatements Minimum number of statements in a sequence
     */
    public StatementExtractor(int minStatements) {
        this(minStatements, 5, true);
    }
    
    /**
     * Create extractor with custom minimum statement count and window growth.
     * 
     * @param minStatements Minimum number of statements in a sequence
     * @param maxWindowGrowth Maximum window size growth beyond minStatements
     */
    public StatementExtractor(int minStatements, int maxWindowGrowth) {
        this(minStatements, maxWindowGrowth, true);
    }
    
    /**
     * Create extractor with full configuration.
     * 
     * @param minStatements Minimum number of statements in a sequence
     * @param maxWindowGrowth Maximum window size growth beyond minStatements
     * @param maximalOnly If true, only extract maximal (longest possible) sequences at each position
     */
    public StatementExtractor(int minStatements, int maxWindowGrowth, boolean maximalOnly) {
        if (minStatements < 1) {
            throw new IllegalArgumentException("Minimum statements must be at least 1");
        }
        if (maxWindowGrowth < 0) {
            throw new IllegalArgumentException("Max window growth must be >= 0");
        }
        this.minStatements = minStatements;
        this.maxWindowGrowth = maxWindowGrowth;
        this.maximalOnly = maximalOnly;
    }
    
    /**
     * Extract all statement sequences from a compilation unit.
     * Uses sliding window to extract overlapping sequences.
     * 
     * @param cu Compilation unit to extract from
     * @param sourceFile Path to source file
     * @return List of statement sequences
     */
    public List<StatementSequence> extractSequences(CompilationUnit cu, Path sourceFile) {
        List<StatementSequence> sequences = new ArrayList<>();
        
        // Normalize path once for all sequences from this file
        Path normalizedSourceFile = sourceFile != null ? sourceFile.toAbsolutePath().normalize() : null;
        
        // Visit all methods and constructors in the compilation unit
        cu.accept(new MethodVisitor(sequences, cu, normalizedSourceFile), null);
        
        return sequences;
    }
    
    /**
     * Visitor to extract sequences from each method/constructor.
     */
    private class MethodVisitor extends VoidVisitorAdapter<Void> {
        private final List<StatementSequence> sequences;
        private final CompilationUnit cu;
        private final Path sourceFile;
        
        /**
         * Creates a new method visitor.
         *
         * @param sequences List to populate with extracted sequences
         * @param cu        The compilation unit being visited
         * @param sourceFile The path to the source file
         */
        MethodVisitor(List<StatementSequence> sequences, CompilationUnit cu, Path sourceFile) {
            this.sequences = sequences;
            this.cu = cu;
            this.sourceFile = sourceFile;
        }
        
        @Override
        public void visit(MethodDeclaration method, Void arg) {
            super.visit(method, arg);
            // Skip methods without body (abstract, interface methods)
            method.getBody().ifPresent(body -> extractFromBlock(body, method));
        }

        @Override
        public void visit(ConstructorDeclaration constructor, Void arg) {
            super.visit(constructor, arg);
            extractFromBlock(constructor.getBody(), constructor);
        }
        
        /**
         * Recursively extract statement sequences from a block and all its nested blocks.
         * This allows detection of duplicates inside try/catch/finally, if/else, loops, etc.
         * 
         * @param block The block to extract from
         * @param callable The containing method or constructor
         */
        private void extractFromBlock(BlockStmt block, CallableDeclaration<?> callable) {
            List<Statement> statements = block.getStatements();
            
            // Extract sliding windows from this block's statements
            extractSlidingWindows(statements, callable);
            
            // Recursively process nested blocks in each statement
            for (Statement stmt : statements) {
                processNestedBlocks(stmt, callable);
            }
        }
        
        /**
         * Process nested blocks within a statement.
         * Handles all statement types that can contain blocks.
         * 
         * @param stmt The statement to process
         * @param callable The containing method or constructor
         */
        private void processNestedBlocks(Statement stmt, CallableDeclaration<?> callable) {
            if (stmt instanceof TryStmt tryStmt) {
                processTryBlock(callable, tryStmt);
            } else if (stmt instanceof IfStmt ifStmt) {
                processIfBlock(callable, ifStmt);
            } else if (stmt instanceof SwitchStmt switchStmt) {
                processSwitchBlock(callable, switchStmt);
            } else if (stmt instanceof NodeWithBody<?> block && block.getBody() instanceof BlockStmt blockStmt) {
                extractFromBlock(blockStmt, callable);
            }
        }

        private void processSwitchBlock(CallableDeclaration<?> callable, SwitchStmt switchStmt) {
            switchStmt.getEntries().forEach(entry -> {
                // Extract from each switch case's statements
                List<Statement> caseStatements = entry.getStatements();
                if (!caseStatements.isEmpty()) {
                    extractSlidingWindows(caseStatements, callable);
                    // Recursively process nested blocks in case statements
                    caseStatements.forEach(s -> processNestedBlocks(s, callable));
                }
            });
        }

        private void processTryBlock(CallableDeclaration<?> callable, TryStmt tryStmt) {
            // Extract from try block
            extractFromBlock(tryStmt.getTryBlock(), callable);
            // Extract from each catch clause
            tryStmt.getCatchClauses().forEach(catchClause ->
                extractFromBlock(catchClause.getBody(), callable)
            );
            // Extract from finally block if present
            tryStmt.getFinallyBlock().ifPresent(finallyBlock ->
                extractFromBlock(finallyBlock, callable)
            );
        }

        private void processIfBlock(CallableDeclaration<?> callable, IfStmt ifStmt) {
            // Extract from then branch
            if (ifStmt.getThenStmt() instanceof BlockStmt blockStmt) {
                extractFromBlock(blockStmt, callable);
            }
            // Extract from else branch if present
            ifStmt.getElseStmt().ifPresent(elseStmt -> {
                if (elseStmt instanceof BlockStmt blockStmt) {
                    extractFromBlock(blockStmt, callable);
                } else if (elseStmt instanceof IfStmt) {
                    // Handle else-if chains
                    processNestedBlocks(elseStmt, callable);
                }
            });
        }

        /**
         * Extract sliding windows of statements with optimized strategy.
         */
        private void extractSlidingWindows(List<Statement> statements, CallableDeclaration<?> callable) {
            int totalStatements = statements.size();
            int effectiveMin = (callable instanceof ConstructorDeclaration) ? 3 : minStatements;

            // SPECIAL CASE: Always extract the full body as a sequence if it meets min requirements
            // This is critical for constructor/method reuse even when one body is longer than another.
            // BUT: Only add it here if the normal window logic WON'T capture it (i.e., if it's too long).
            if (totalStatements >= effectiveMin) {
                // Check if this is indeed the full body of the callable (not a nested block)
                Optional<BlockStmt> bodyOpt = getCallableBody(callable);
                if (bodyOpt.isPresent() && bodyOpt.get().getStatements() == statements
                        && totalStatements > effectiveMin + maxWindowGrowth) {
                    sequences.add(createSequence(statements, callable));
                }
            }

            // Skip if not enough statements (redundant but safe)
            if (totalStatements < effectiveMin) {
                return;
            }
            
            // Targeted Relaxation: Allow windowed extraction for constructors to support prefix reuse (this())
            // even if global setting is maximal_only. Methods stay maximal to prevent regression.
            if (StatementExtractor.this.maximalOnly && !(callable instanceof ConstructorDeclaration)) {
                extractMaximalSequences(statements, callable, totalStatements, effectiveMin);
            } else {
                extractLimitedWindowSizes(statements, callable, totalStatements, effectiveMin);
            }
        }
        
        private void extractMaximalSequences(List<Statement> statements, CallableDeclaration<?> callable, int totalStatements, int effectiveMin) {
            // For each starting position, create the longest possible sequence
            for (int start = 0; start <= totalStatements - effectiveMin; start++) {
                // Calculate the maximum size we can extract from this position
                int remainingStatements = totalStatements - start;
                int maxPossibleSize = Math.min(
                    effectiveMin + StatementExtractor.this.maxWindowGrowth,
                    remainingStatements
                );
                
                // Only create the largest window from this position
                List<Statement> window = statements.subList(start, start + maxPossibleSize);
                StatementSequence sequence = createSequence(window, callable);
                sequences.add(sequence);
            }
        }
        
        private void extractLimitedWindowSizes(List<Statement> statements, CallableDeclaration<?> callable, int totalStatements, int effectiveMin) {
            // Limit window size growth to prevent exponential explosion
            final int maxWindowSize = Math.min(
                effectiveMin + StatementExtractor.this.maxWindowGrowth,
                totalStatements
            );
            
            // Extract windows with limited size variation
            for (int start = 0; start <= totalStatements - effectiveMin; start++) {
                for (int windowSize = effectiveMin; windowSize <= maxWindowSize && start + windowSize <= totalStatements; windowSize++) {
                    int end = start + windowSize;
                    
                    List<Statement> window = statements.subList(start, end);
                    StatementSequence sequence = createSequence(window, callable);
                    sequences.add(sequence);
                }
            }
        }
        
        /**
         * Create a StatementSequence from a list of statements.
         */
        private StatementSequence createSequence(List<Statement> statements, CallableDeclaration<?> callable) {
            // Get range from first to last statement
            Statement first = statements.getFirst();
            Statement last = statements.getLast();

            Range range = BoundaryRefiner.createRange(first, last);

            // Calculate actual statement index within the method (0-based)
            int startOffset = calculateStatementIndex(first, callable);
            
            return new StatementSequence(
                new ArrayList<>(statements),  // Defensive copy
                range,
                startOffset,
                callable,
                cu,
                sourceFile
            );
        }
        
        /**
         * Calculate the actual 0-based index of a statement within its containing method.
         */
        private int calculateStatementIndex(Statement targetStmt, CallableDeclaration<?> callable) {
            Optional<BlockStmt> body = getCallableBody(callable);
            if (body.isEmpty() || body.get().getStatements().isEmpty()) {
                return 0;
            }

            List<Statement> methodStmts = body.get().getStatements();
            int index = findExactStatementIndex(methodStmts, targetStmt);
            if (index != -1) {
                return index;
            }

            return findRangeStatementIndex(methodStmts, targetStmt);
        }

        private Optional<BlockStmt> getCallableBody(CallableDeclaration<?> callable) {
            if (callable instanceof MethodDeclaration m) {
                return m.getBody();
            } else if (callable instanceof ConstructorDeclaration c) {
                return Optional.of(c.getBody());
            }
            return Optional.empty();
        }

        private int findExactStatementIndex(List<Statement> stmts, Statement target) {
            for (int i = 0; i < stmts.size(); i++) {
                if (stmts.get(i) == target) {
                    return i;
                }
            }
            return -1;
        }

        private int findRangeStatementIndex(List<Statement> stmts, Statement target) {
            if (target.getRange().isEmpty()) {
                return 0;
            }
            com.github.javaparser.Range targetRange = target.getRange().get();
            for (int i = 0; i < stmts.size(); i++) {
                if (stmts.get(i).getRange().isPresent()) {
                    com.github.javaparser.Range stmtRange = stmts.get(i).getRange().get();
                    if (stmtRange.begin.equals(targetRange.begin)) {
                        return i;
                    }
                }
            }
            return 0;
        }
    }
}
