package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import java.util.Map;

/**
 * Normalizes a method body by ensuring consistent variable naming.
 * This is used to compare methods for equivalence.
 */
public class NormalizationVisitor extends ModifierVisitor<Map<String, String>> {

    @Override
    public Visitable visit(Parameter n, Map<String, String> arg) {
        String name = n.getNameAsString();
        if (arg.containsKey(name)) {
            n.setName(arg.get(name));
        }
        return super.visit(n, arg);
    }

    @Override
    public Visitable visit(VariableDeclarator n, Map<String, String> arg) {
        String name = n.getNameAsString();
        if (arg.containsKey(name)) {
            n.setName(arg.get(name));
        }
        return super.visit(n, arg);
    }

    @Override
    public Visitable visit(NameExpr n, Map<String, String> arg) {
        String name = n.getNameAsString();
        if (arg.containsKey(name)) {
            n.setName(arg.get(name));
        }
        return super.visit(n, arg);
    }
}
