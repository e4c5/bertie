package com.raditha.dedup;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class JavadocAuditor {

    @Test
    public void audit() throws IOException {
        Path start = Paths.get("src/main/java");
        try (Stream<Path> stream = Files.walk(start)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .forEach(this::checkFile);
        }
    }

    private void checkFile(Path path) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(path);

            // Check top level types
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                if (c.isPublic() && !c.getJavadoc().isPresent()) {
                    System.out.println("MISSING_TYPE_DOC: " + path + " : " + c.getNameAsString());
                }

                c.getMethods().forEach(m -> {
                    if (m.isPublic() && !m.getJavadoc().isPresent()) {
                        System.out.println("MISSING_METHOD_DOC: " + path + " : " + c.getNameAsString() + "." + m.getNameAsString());
                    }
                });

                c.getConstructors().forEach(con -> {
                     if (con.isPublic() && !con.getJavadoc().isPresent()) {
                        System.out.println("MISSING_CONSTRUCTOR_DOC: " + path + " : " + c.getNameAsString() + ".<init>");
                    }
                });
            });

            cu.findAll(EnumDeclaration.class).forEach(e -> {
                 if (e.isPublic() && !e.getJavadoc().isPresent()) {
                    System.out.println("MISSING_TYPE_DOC: " + path + " : " + e.getNameAsString());
                }
                e.getMethods().forEach(m -> {
                     if (m.isPublic() && !m.getJavadoc().isPresent()) {
                        System.out.println("MISSING_METHOD_DOC: " + path + " : " + e.getNameAsString() + "." + m.getNameAsString());
                    }
                });
                 e.getConstructors().forEach(con -> {
                     if (con.isPublic() && !con.getJavadoc().isPresent()) {
                        System.out.println("MISSING_CONSTRUCTOR_DOC: " + path + " : " + e.getNameAsString() + ".<init>");
                    }
                });
            });

             cu.findAll(RecordDeclaration.class).forEach(r -> {
                 if (r.isPublic() && !r.getJavadoc().isPresent()) {
                    System.out.println("MISSING_TYPE_DOC: " + path + " : " + r.getNameAsString());
                }
                r.getMethods().forEach(m -> {
                     if (m.isPublic() && !m.getJavadoc().isPresent()) {
                        System.out.println("MISSING_METHOD_DOC: " + path + " : " + r.getNameAsString() + "." + m.getNameAsString());
                    }
                });
                 r.getConstructors().forEach(con -> {
                     if (con.isPublic() && !con.getJavadoc().isPresent()) {
                        System.out.println("MISSING_CONSTRUCTOR_DOC: " + path + " : " + r.getNameAsString() + ".<init>");
                    }
                });
            });


        } catch (Exception e) {
            System.err.println("Failed to parse " + path + ": " + e.getMessage());
        }
    }
}
