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
    
    /**
     * Create extractor with default minimum statement count (5).
     */
    public StatementExtractor() {
        this(5);
    }
    
    /**
     * Create extractor with custom minimum statement count.
     * 
     * @param minStatements Minimum number of statements in a sequence
     */
    public StatementExtractor(int minStatements) {
        if (minStatements < 1) {
            throw new IllegalArgumentException("Minimum statements must be at least 1");
        }
        this.minStatements = minStatements;
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
         * Extract all possible sliding windows of statements.
         * For a method with N statements and minimum M statements,
         * extracts windows: [0..M-1], [1..M], [2..M+1], ..., [N-M..N-1]
         */
        private void extractSlidingWindows(List<Statement> statements, MethodDeclaration method) {
            int totalStatements = statements.size();
            
            // Skip if not enough statements
            if (totalStatements < minStatements) {
                return;
            }
            
            // Extract all possible windows
            for (int start = 0; start <= totalStatements - minStatements; start++) {
                for (int windowSize = minStatements; windowSize <= totalStatements - start; windowSize++) {
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
