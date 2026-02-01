package com.raditha.dedup;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Verifies that all public API members have Javadoc comments.
 */
public class JavadocAuditor {

    @Test
    public void audit() throws IOException {
        // Configure Parser for Java 17 to support records and other modern features
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        StaticJavaParser.setConfiguration(config);

        Path start = Paths.get("src/main/java");
        if (!Files.exists(start)) {
            // Skip test if source directory doesn't exist (e.g. inside a submodule during partial build)
            return;
        }

        List<String> violations = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(start)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> checkFile(p, violations));
        }

        if (!violations.isEmpty()) {
            Assertions.fail("Found missing Javadocs:\n" + String.join("\n", violations));
        }
    }

    private void checkFile(Path path, List<String> violations) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(path);

            // Check top level types
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                if (c.isPublic() && !c.getJavadoc().isPresent()) {
                    violations.add("MISSING_TYPE_DOC: " + path + " : " + c.getNameAsString());
                }

                c.getMethods().forEach(m -> {
                    if (m.isPublic() && !m.getJavadoc().isPresent()) {
                        violations.add("MISSING_METHOD_DOC: " + path + " : " + c.getNameAsString() + "." + m.getNameAsString());
                    }
                });

                c.getConstructors().forEach(con -> {
                     if (con.isPublic() && !con.getJavadoc().isPresent()) {
                        violations.add("MISSING_CONSTRUCTOR_DOC: " + path + " : " + c.getNameAsString() + ".<init>");
                    }
                });
            });

            cu.findAll(EnumDeclaration.class).forEach(e -> {
                 if (e.isPublic() && !e.getJavadoc().isPresent()) {
                    violations.add("MISSING_TYPE_DOC: " + path + " : " + e.getNameAsString());
                }
                e.getMethods().forEach(m -> {
                     if (m.isPublic() && !m.getJavadoc().isPresent()) {
                        violations.add("MISSING_METHOD_DOC: " + path + " : " + e.getNameAsString() + "." + m.getNameAsString());
                    }
                });
                 e.getConstructors().forEach(con -> {
                     if (con.isPublic() && !con.getJavadoc().isPresent()) {
                        violations.add("MISSING_CONSTRUCTOR_DOC: " + path + " : " + e.getNameAsString() + ".<init>");
                    }
                });
            });

             cu.findAll(RecordDeclaration.class).forEach(r -> {
                 if (r.isPublic() && !r.getJavadoc().isPresent()) {
                    violations.add("MISSING_TYPE_DOC: " + path + " : " + r.getNameAsString());
                }
                r.getMethods().forEach(m -> {
                     if (m.isPublic() && !m.getJavadoc().isPresent()) {
                        violations.add("MISSING_METHOD_DOC: " + path + " : " + r.getNameAsString() + "." + m.getNameAsString());
                    }
                });
                 r.getConstructors().forEach(con -> {
                     if (con.isPublic() && !con.getJavadoc().isPresent()) {
                        violations.add("MISSING_CONSTRUCTOR_DOC: " + path + " : " + r.getNameAsString() + ".<init>");
                    }
                });
            });


        } catch (Exception e) {
            // Report parse failures as violations to ensure we don't silently skip files
            violations.add("FAILED_TO_PARSE: " + path + " : " + e.getMessage());
        }
    }
}
