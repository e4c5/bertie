package com.raditha.dedup.util;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;

/**
 * Utility class for common AST operations.
 */
public class ASTUtility {

    private ASTUtility() {
        /* this is only a utility class */
    }
    /**
     * Get the source file path from a CompilationUnit.
     *
     * @param cu The CompilationUnit.
     * @return The source file path.
     * @throws java.util.NoSuchElementException if the path is not available in storage.
     */
    public static Path getSourcePath(CompilationUnit cu) {
        return cu.getStorage()
                .map(CompilationUnit.Storage::getPath)
                .orElseThrow(() -> new IllegalStateException("CompilationUnit has no storage path"))
                .toAbsolutePath().normalize();
    }
}
