package com.raditha.dedup.extraction;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.raditha.dedup.analysis.BoundaryRefiner;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts statement sequences from Java source files using sliding window.
 * Focuses on method bodies to find potential code duplicates.
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
        
        // Visit all methods in the compilation unit
        cu.accept(new MethodVisitor(sequences, cu, sourceFile), null);
        
        return sequences;
    }
    
    /**
     * Visitor to extract sequences from each method.
     */
    private class MethodVisitor extends VoidVisitorAdapter<Void> {
        private final List<StatementSequence> sequences;
        private final CompilationUnit cu;
        private final Path sourceFile;
        
        MethodVisitor(List<StatementSequence> sequences, CompilationUnit cu, Path sourceFile) {
            this.sequences = sequences;
            this.cu = cu;
            this.sourceFile = sourceFile;
        }
        
        @Override
        public void visit(MethodDeclaration method, Void arg) {
            super.visit(method, arg);
            
            // Skip methods without body (abstract, interface methods)
            if (method.getBody().isPresent()) {
                BlockStmt body = method.getBody().get();
                // Recursively extract from all nested blocks
                extractFromBlock(body, method);
            }
        }
        
        /**
         * Recursively extract statement sequences from a block and all its nested blocks.
         * This allows detection of duplicates inside try/catch/finally, if/else, loops, etc.
         * 
         * @param block The block to extract from
         * @param method The containing method
         */
        private void extractFromBlock(BlockStmt block, MethodDeclaration method) {
            List<Statement> statements = block.getStatements();
            
            // Extract sliding windows from this block's statements
            extractSlidingWindows(statements, method);
            
            // Recursively process nested blocks in each statement
            for (Statement stmt : statements) {
                processNestedBlocks(stmt, method);
            }
        }
        
        /**
         * Process nested blocks within a statement.
         * Handles all statement types that can contain blocks.
         * 
         * @param stmt The statement to process
         * @param method The containing method
         */
        private void processNestedBlocks(Statement stmt, MethodDeclaration method) {
            if (stmt instanceof TryStmt) {
                TryStmt tryStmt = (TryStmt) stmt;
                // Extract from try block
                extractFromBlock(tryStmt.getTryBlock(), method);
                // Extract from each catch clause
                tryStmt.getCatchClauses().forEach(catchClause -> 
                    extractFromBlock(catchClause.getBody(), method)
                );
                // Extract from finally block if present
                tryStmt.getFinallyBlock().ifPresent(finallyBlock -> 
                    extractFromBlock(finallyBlock, method)
                );
            } else if (stmt instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) stmt;
                // Extract from then branch
                if (ifStmt.getThenStmt() instanceof BlockStmt) {
                    extractFromBlock((BlockStmt) ifStmt.getThenStmt(), method);
                }
                // Extract from else branch if present
                ifStmt.getElseStmt().ifPresent(elseStmt -> {
                    if (elseStmt instanceof BlockStmt) {
                        extractFromBlock((BlockStmt) elseStmt, method);
                    } else if (elseStmt instanceof IfStmt) {
                        // Handle else-if chains
                        processNestedBlocks(elseStmt, method);
                    }
                });
            } else if (stmt instanceof WhileStmt) {
                WhileStmt whileStmt = (WhileStmt) stmt;
                if (whileStmt.getBody() instanceof BlockStmt) {
                    extractFromBlock((BlockStmt) whileStmt.getBody(), method);
                }
            } else if (stmt instanceof ForStmt) {
                ForStmt forStmt = (ForStmt) stmt;
                if (forStmt.getBody() instanceof BlockStmt) {
                    extractFromBlock((BlockStmt) forStmt.getBody(), method);
                }
            } else if (stmt instanceof ForEachStmt) {
                ForEachStmt forEachStmt = (ForEachStmt) stmt;
                if (forEachStmt.getBody() instanceof BlockStmt) {
                    extractFromBlock((BlockStmt) forEachStmt.getBody(), method);
                }
            } else if (stmt instanceof DoStmt) {
                DoStmt doStmt = (DoStmt) stmt;
                if (doStmt.getBody() instanceof BlockStmt) {
                    extractFromBlock((BlockStmt) doStmt.getBody(), method);
                }
            } else if (stmt instanceof SynchronizedStmt) {
                SynchronizedStmt syncStmt = (SynchronizedStmt) stmt;
                extractFromBlock(syncStmt.getBody(), method);
            } else if (stmt instanceof SwitchStmt) {
                SwitchStmt switchStmt = (SwitchStmt) stmt;
                switchStmt.getEntries().forEach(entry -> {
                    // Extract from each switch case's statements
                    List<Statement> caseStatements = entry.getStatements();
                    if (!caseStatements.isEmpty()) {
                        extractSlidingWindows(caseStatements, method);
                        // Recursively process nested blocks in case statements
                        caseStatements.forEach(s -> processNestedBlocks(s, method));
                    }
                });
            }
        }
        
        /**
         * Extract sliding windows of statements with optimized strategy.
         * 
         * Two modes:
         * 1. maximalOnly=true: Extract only the longest possible sequence at each position.
         *    This generates O(N) sequences and avoids comparing smaller duplicates 
         *    that are subsets of larger ones.
         *    For a method with 100 statements: ~100 sequences
         * 
         * 2. maximalOnly=false: Extract limited-size windows (minStatements to minStatements + maxWindowGrowth).
         *    For a method with 100 statements and maxWindowGrowth=5: ~576 sequences
         */
        private void extractSlidingWindows(List<Statement> statements, MethodDeclaration method) {
            int totalStatements = statements.size();
            
            // Skip if not enough statements
            if (totalStatements < minStatements) {
                return;
            }
            
            if (StatementExtractor.this.maximalOnly) {
                // Strategy 1: Extract only maximal sequences (one per starting position)
                // This dramatically reduces sequences when you only want the largest duplicates
                extractMaximalSequences(statements, method, totalStatements);
            } else {
                // Strategy 2: Extract limited window sizes for more coverage
                extractLimitedWindowSizes(statements, method, totalStatements);
            }
        }
        
        /**
         * Extract only maximal (longest possible) sequence at each starting position.
         * Generates exactly (totalStatements - minStatements + 1) sequences.
         */
        private void extractMaximalSequences(List<Statement> statements, MethodDeclaration method, int totalStatements) {
            // For each starting position, create the longest possible sequence
            for (int start = 0; start <= totalStatements - minStatements; start++) {
                // Calculate the maximum size we can extract from this position
                int remainingStatements = totalStatements - start;
                int maxPossibleSize = Math.min(
                    minStatements + StatementExtractor.this.maxWindowGrowth,
                    remainingStatements
                );
                
                // Only create the largest window from this position
                List<Statement> window = statements.subList(start, start + maxPossibleSize);
                StatementSequence sequence = createSequence(window, method);
                sequences.add(sequence);
            }
        }
        
        /**
         * Extract windows with limited size variation (original strategy).
         * Generates multiple window sizes per position for better duplicate detection coverage.
         */
        private void extractLimitedWindowSizes(List<Statement> statements, MethodDeclaration method, int totalStatements) {
            // Limit window size growth to prevent exponential explosion
            final int maxWindowSize = Math.min(
                minStatements + StatementExtractor.this.maxWindowGrowth,
                totalStatements
            );
            
            // Extract windows with limited size variation
            for (int start = 0; start <= totalStatements - minStatements; start++) {
                for (int windowSize = minStatements; windowSize <= maxWindowSize && start + windowSize <= totalStatements; windowSize++) {
                    int end = start + windowSize;
                    
                    List<Statement> window = statements.subList(start, end);
                    StatementSequence sequence = createSequence(window, method);
                    sequences.add(sequence);
                }
            }
        }
        
        /**
         * Create a StatementSequence from a list of statements.
         */
        private StatementSequence createSequence(List<Statement> statements, MethodDeclaration method) {
            // Get range from first to last statement
            Statement first = statements.getFirst();
            Statement last = statements.getLast();

            Range range = BoundaryRefiner.createRange(first, last);

            // Calculate start offset (approximate - could be improved)
            int startOffset = calculateOffset(first);
            
            return new StatementSequence(
                new ArrayList<>(statements),  // Defensive copy
                range,
                startOffset,
                method,
                cu,
                sourceFile
            );
        }
        
        /**
         * Calculate approximate character offset for a statement.
         * This is a simple approximation based on line numbers.
         */
        private int calculateOffset(Statement stmt) {
            return stmt.getRange()
                .map(r -> r.begin.line * 80)  // Approximate: 80 chars per line
                .orElse(0);
        }
    }
}
