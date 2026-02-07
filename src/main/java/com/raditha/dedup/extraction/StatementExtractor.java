package com.raditha.dedup.extraction;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithBody;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.raditha.dedup.analysis.BoundaryRefiner;
import com.raditha.dedup.model.ContainerType;
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
    
    public List<StatementSequence> extractSequences(CompilationUnit cu) {
        return extractSequences(cu, com.raditha.dedup.util.ASTUtility.getSourcePath(cu));
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
            method.getBody().ifPresent(body -> extractFromBlock(body, method, ContainerType.METHOD));
        }

        @Override
        public void visit(ConstructorDeclaration constructor, Void arg) {
            super.visit(constructor, arg);
            extractFromBlock(constructor.getBody(), constructor, ContainerType.CONSTRUCTOR);
        }

        @Override
        public void visit(InitializerDeclaration initializer, Void arg) {
            // Don't call super.visit() to prevent descending into nested constructs
            // They will be visited separately in their own context
            ContainerType type = initializer.isStatic() 
                ? ContainerType.STATIC_INITIALIZER 
                : ContainerType.INSTANCE_INITIALIZER;
            extractFromBlock(initializer.getBody(), initializer, type);
        }

        @Override
        public void visit(LambdaExpr lambda, Void arg) {
            // Don't call super.visit() to prevent descending into nested constructs
            // Only process block-bodied lambdas
            if (lambda.getBody().isBlockStmt()) {
                extractFromBlock(lambda.getBody().asBlockStmt(), lambda, ContainerType.LAMBDA);
            }
        }
        
        /**
         * Recursively extract statement sequences from a block and all its nested blocks.
         * This allows detection of duplicates inside try/catch/finally, if/else, loops, etc.
         * 
         * @param block The block to extract from
         * @param container The containing node (method, constructor, initializer, or lambda)
         * @param containerType The type of container
         */
        private void extractFromBlock(BlockStmt block, Node container, ContainerType containerType) {
            List<Statement> statements = block.getStatements();
            
            // Extract sliding windows from this block's statements
            extractSlidingWindows(statements, container, containerType);
            
            // Recursively process nested blocks in each statement
            for (Statement stmt : statements) {
                processNestedBlocks(stmt, container, containerType);
            }
        }
        
        /**
         * Process nested blocks within a statement.
         * Handles all statement types that can contain blocks.
         * IMPORTANT: Does NOT descend into InitializerDeclaration or LambdaExpr nodes
         * as they are handled separately by their own visitor methods.
         * 
         * @param stmt The statement to process
         * @param container The containing node
         * @param containerType The type of container
         */
        private void processNestedBlocks(Statement stmt, Node container, ContainerType containerType) {
            if (stmt instanceof TryStmt tryStmt) {
                processTryBlock(container, containerType, tryStmt);
            } else if (stmt instanceof IfStmt ifStmt) {
                processIfBlock(container, containerType, ifStmt);
            } else if (stmt instanceof SwitchStmt switchStmt) {
                processSwitchBlock(container, containerType, switchStmt);
            } else if (stmt instanceof NodeWithBody<?> block && block.getBody() instanceof BlockStmt blockStmt) {
                extractFromBlock(blockStmt, container, containerType);
            }
        }

        private void processSwitchBlock(Node container, ContainerType containerType, SwitchStmt switchStmt) {
            switchStmt.getEntries().forEach(entry -> {
                // Extract from each switch case's statements
                List<Statement> caseStatements = entry.getStatements();
                if (!caseStatements.isEmpty()) {
                    extractSlidingWindows(caseStatements, container, containerType);
                    // Recursively process nested blocks in case statements
                    caseStatements.forEach(s -> processNestedBlocks(s, container, containerType));
                }
            });
        }

        private void processTryBlock(Node container, ContainerType containerType, TryStmt tryStmt) {
            // Extract from try block
            extractFromBlock(tryStmt.getTryBlock(), container, containerType);
            // Extract from each catch clause
            tryStmt.getCatchClauses().forEach(catchClause ->
                extractFromBlock(catchClause.getBody(), container, containerType)
            );
            // Extract from finally block if present
            tryStmt.getFinallyBlock().ifPresent(finallyBlock ->
                extractFromBlock(finallyBlock, container, containerType)
            );
        }

        private void processIfBlock(Node container, ContainerType containerType, IfStmt ifStmt) {
            // Extract from then branch
            if (ifStmt.getThenStmt() instanceof BlockStmt blockStmt) {
                extractFromBlock(blockStmt, container, containerType);
            }
            // Extract from else branch if present
            ifStmt.getElseStmt().ifPresent(elseStmt -> {
                if (elseStmt instanceof BlockStmt blockStmt) {
                    extractFromBlock(blockStmt, container, containerType);
                } else if (elseStmt instanceof IfStmt) {
                    // Handle else-if chains
                    processNestedBlocks(elseStmt, container, containerType);
                }
            });
        }

        /**
         * Extract sliding windows of statements with optimized strategy.
         */
        private void extractSlidingWindows(List<Statement> statements, Node container, ContainerType containerType) {
            int totalStatements = statements.size();
            int effectiveMin = minStatements;

            // SPECIAL CASE: Always extract the full body as a sequence if it meets min requirements
            // This is critical for constructor/method reuse even when one body is longer than another.
            // BUT: Only add it here if the normal window logic WON'T capture it (i.e., if it's too long).
            if (totalStatements >= effectiveMin) {
                // Check if this is indeed the full body of the container (not a nested block)
                Optional<BlockStmt> bodyOpt = getContainerBody(container);
                if (bodyOpt.isPresent() && bodyOpt.get().getStatements() == statements
                        && totalStatements > effectiveMin + maxWindowGrowth) {
                    sequences.add(createSequence(statements, container, containerType));
                }
            }

            // Skip if not enough statements (redundant but safe)
            if (totalStatements < effectiveMin) {
                return;
            }
            
            // Targeted Relaxation: Allow windowed extraction for constructors to support prefix reuse (this())
            // even if global setting is maximal_only. Methods stay maximal to prevent regression.
            if (StatementExtractor.this.maximalOnly && containerType != ContainerType.CONSTRUCTOR) {
                extractMaximalSequences(statements, container, containerType, totalStatements, effectiveMin);
            } else {
                extractLimitedWindowSizes(statements, container, containerType, totalStatements, effectiveMin);
            }
        }
        
        private void extractMaximalSequences(List<Statement> statements, Node container, ContainerType containerType, int totalStatements, int effectiveMin) {
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
                StatementSequence sequence = createSequence(window, container, containerType);
                sequences.add(sequence);
            }
        }
        
        private void extractLimitedWindowSizes(List<Statement> statements, Node container, ContainerType containerType, int totalStatements, int effectiveMin) {
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
                    StatementSequence sequence = createSequence(window, container, containerType);
                    sequences.add(sequence);
                }
            }
        }
        
        /**
         * Create a StatementSequence from a list of statements.
         */
        private StatementSequence createSequence(List<Statement> statements, Node container, ContainerType containerType) {
            // Get range from first to last statement
            Statement first = statements.getFirst();
            Statement last = statements.getLast();

            Range range = BoundaryRefiner.createRange(first, last);

            // Calculate actual statement index within the container (0-based)
            int startOffset = calculateStatementIndex(first, container);
            
            return new StatementSequence(
                new ArrayList<>(statements),  // Defensive copy
                range,
                startOffset,
                container,
                containerType,
                cu,
                sourceFile
            );
        }
        
        /**
         * Calculate the actual 0-based index of a statement within its containing node.
         */
        private int calculateStatementIndex(Statement targetStmt, Node container) {
            Optional<BlockStmt> body = getContainerBody(container);
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

        private Optional<BlockStmt> getContainerBody(Node container) {
            if (container instanceof MethodDeclaration m) {
                return m.getBody();
            } else if (container instanceof ConstructorDeclaration c) {
                return Optional.of(c.getBody());
            } else if (container instanceof InitializerDeclaration init) {
                return Optional.of(init.getBody());
            } else if (container instanceof LambdaExpr lambda) {
                if (lambda.getBody().isBlockStmt()) {
                    return Optional.of(lambda.getBody().asBlockStmt());
                }
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
