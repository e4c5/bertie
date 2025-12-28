package com.raditha.dedup.extraction;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
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
            if (method.getBody().isEmpty()) {
                return;
            }
            
            BlockStmt body = method.getBody().get();
            List<Statement> statements = body.getStatements();
            
            // Apply sliding window
            extractSlidingWindows(statements, method);
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
            
            com.github.javaparser.Range firstRange = first.getRange()
                .orElseThrow(() -> new IllegalStateException("Statement missing range"));
            com.github.javaparser.Range lastRange = last.getRange()
                .orElseThrow(() -> new IllegalStateException("Statement missing range"));
            
            // Create combined range (note: Range record takes startLine, endLine, startColumn, endColumn)
            Range range = new Range(
                firstRange.begin.line,
                lastRange.end.line,
                firstRange.begin.column,
                lastRange.end.column
            );
            
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
