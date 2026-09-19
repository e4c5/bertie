package com.raditha.dedup.analysis;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.raditha.dedup.model.StatementSequence;

import java.util.List;

/**
 * Classifies how the control flow of two duplicate sequences differs.
 *
 * <ul>
 *   <li>{@link Kind#NONE}: identical control-flow skeleton and identical conditions.</li>
 *   <li>{@link Kind#PARAMETERIZABLE}: identical skeleton (same statement kinds, nesting,
 *       branch/catch structure, jump statements) but a loop or branch <em>condition</em>
 *       differs. Such a difference can in principle be lifted into a parameter, but the
 *       extracted helper must be reviewed manually.</li>
 *   <li>{@link Kind#UNSAFE}: the skeleton itself differs (extra/missing branch, different
 *       statement kinds, differing jumps, catch clauses, ...). Extracting a single helper
 *       would change behaviour for at least one occurrence.</li>
 * </ul>
 */
public class ControlFlowVariationAnalyzer {

    public enum Kind {
        NONE, PARAMETERIZABLE, UNSAFE
    }

    public record Result(Kind kind, String detail) {
        public static final Result NONE = new Result(Kind.NONE, "");

        static Result parameterizable(String detail) {
            return new Result(Kind.PARAMETERIZABLE, detail);
        }

        static Result unsafe(String detail) {
            return new Result(Kind.UNSAFE, detail);
        }

        Result worst(Result other) {
            return other.kind.ordinal() > kind.ordinal() ? other : this;
        }
    }

    public Result analyze(StatementSequence a, StatementSequence b) {
        if (a == null || b == null || a.statements() == null || b.statements() == null) {
            return Result.NONE;
        }
        return compareLists(a.statements(), b.statements());
    }

    private Result compareLists(List<Statement> a, List<Statement> b) {
        if (a.size() != b.size()) {
            // Only relevant when the differing statements carry control flow
            boolean cfA = a.stream().anyMatch(ControlFlowVariationAnalyzer::isControlFlow);
            boolean cfB = b.stream().anyMatch(ControlFlowVariationAnalyzer::isControlFlow);
            if (cfA || cfB) {
                return Result.unsafe("different number of statements in a control-flow context ("
                        + a.size() + " vs " + b.size() + ")");
            }
            return Result.NONE;
        }
        Result result = Result.NONE;
        for (int i = 0; i < a.size(); i++) {
            result = result.worst(compare(a.get(i), b.get(i)));
            if (result.kind == Kind.UNSAFE) {
                return result;
            }
        }
        return result;
    }

    private Result compare(Statement s1, Statement s2) {
        if (s1.getClass() != s2.getClass()) {
            if (isControlFlow(s1) || isControlFlow(s2)) {
                return Result.unsafe(s1.getClass().getSimpleName() + " vs " + s2.getClass().getSimpleName());
            }
            return Result.NONE;
        }

        if (s1 instanceof IfStmt if1) {
            IfStmt if2 = (IfStmt) s2;
            if (if1.getElseStmt().isPresent() != if2.getElseStmt().isPresent()) {
                return Result.unsafe("if/else branch present in only one duplicate");
            }
            Result r = conditionResult(if1.getCondition(), if2.getCondition(), "if");
            r = r.worst(compare(if1.getThenStmt(), if2.getThenStmt()));
            if (if1.getElseStmt().isPresent()) {
                r = r.worst(compare(if1.getElseStmt().get(), if2.getElseStmt().get()));
            }
            return r;
        }
        if (s1 instanceof WhileStmt w1) {
            WhileStmt w2 = (WhileStmt) s2;
            return conditionResult(w1.getCondition(), w2.getCondition(), "while")
                    .worst(compare(w1.getBody(), w2.getBody()));
        }
        if (s1 instanceof DoStmt d1) {
            DoStmt d2 = (DoStmt) s2;
            return conditionResult(d1.getCondition(), d2.getCondition(), "do-while")
                    .worst(compare(d1.getBody(), d2.getBody()));
        }
        if (s1 instanceof ForStmt f1) {
            ForStmt f2 = (ForStmt) s2;
            if (f1.getInitialization().size() != f2.getInitialization().size()
                    || f1.getUpdate().size() != f2.getUpdate().size()
                    || f1.getCompare().isPresent() != f2.getCompare().isPresent()) {
                return Result.unsafe("for-loop header structure differs");
            }
            Result r = Result.NONE;
            if (f1.getCompare().isPresent()) {
                r = conditionResult(f1.getCompare().get(), f2.getCompare().get(), "for");
            }
            if (!sameText(f1.getInitialization(), f2.getInitialization())) {
                r = r.worst(Result.parameterizable("for-loop initialisation differs"));
            }
            if (!sameText(f1.getUpdate(), f2.getUpdate())) {
                r = r.worst(Result.unsafe("for-loop update differs"));
            }
            return r.worst(compare(f1.getBody(), f2.getBody()));
        }
        if (s1 instanceof ForEachStmt fe1) {
            ForEachStmt fe2 = (ForEachStmt) s2;
            Result r = Result.NONE;
            if (!fe1.getIterable().toString().equals(fe2.getIterable().toString())) {
                r = Result.parameterizable("for-each iterable differs");
            }
            return r.worst(compare(fe1.getBody(), fe2.getBody()));
        }
        if (s1 instanceof SwitchStmt sw1) {
            SwitchStmt sw2 = (SwitchStmt) s2;
            if (sw1.getEntries().size() != sw2.getEntries().size()) {
                return Result.unsafe("switch has a different number of cases");
            }
            Result r = Result.NONE;
            if (!sw1.getSelector().toString().equals(sw2.getSelector().toString())) {
                r = Result.parameterizable("switch selector differs");
            }
            for (int i = 0; i < sw1.getEntries().size(); i++) {
                SwitchEntry e1 = sw1.getEntries().get(i);
                SwitchEntry e2 = sw2.getEntries().get(i);
                if (!sameText(e1.getLabels(), e2.getLabels())) {
                    return Result.unsafe("switch case labels differ");
                }
                r = r.worst(compareLists(e1.getStatements(), e2.getStatements()));
            }
            return r;
        }
        if (s1 instanceof TryStmt t1) {
            TryStmt t2 = (TryStmt) s2;
            if (t1.getCatchClauses().size() != t2.getCatchClauses().size()
                    || t1.getFinallyBlock().isPresent() != t2.getFinallyBlock().isPresent()
                    || t1.getResources().size() != t2.getResources().size()) {
                return Result.unsafe("try/catch/finally structure differs");
            }
            for (int i = 0; i < t1.getCatchClauses().size(); i++) {
                String type1 = t1.getCatchClauses().get(i).getParameter().getType().toString();
                String type2 = t2.getCatchClauses().get(i).getParameter().getType().toString();
                if (!type1.equals(type2)) {
                    return Result.unsafe("caught exception types differ: " + type1 + " vs " + type2);
                }
            }
            Result r = compare(t1.getTryBlock(), t2.getTryBlock());
            for (int i = 0; i < t1.getCatchClauses().size(); i++) {
                r = r.worst(compare(t1.getCatchClauses().get(i).getBody(), t2.getCatchClauses().get(i).getBody()));
            }
            if (t1.getFinallyBlock().isPresent()) {
                r = r.worst(compare(t1.getFinallyBlock().get(), t2.getFinallyBlock().get()));
            }
            return r;
        }
        if (s1 instanceof BlockStmt b1) {
            return compareLists(b1.getStatements(), ((BlockStmt) s2).getStatements());
        }
        if (s1 instanceof LabeledStmt l1) {
            return compare(l1.getStatement(), ((LabeledStmt) s2).getStatement());
        }
        if (s1 instanceof SynchronizedStmt sy1) {
            return compare(sy1.getBody(), ((SynchronizedStmt) s2).getBody());
        }
        if (s1 instanceof ReturnStmt r1) {
            ReturnStmt r2 = (ReturnStmt) s2;
            if (r1.getExpression().isPresent() != r2.getExpression().isPresent()) {
                return Result.unsafe("return with and without value");
            }
            return Result.NONE;
        }
        if (s1 instanceof BreakStmt br1) {
            BreakStmt br2 = (BreakStmt) s2;
            if (!br1.getLabel().equals(br2.getLabel())) {
                return Result.unsafe("break targets differ");
            }
            return Result.NONE;
        }
        if (s1 instanceof ContinueStmt c1) {
            ContinueStmt c2 = (ContinueStmt) s2;
            if (!c1.getLabel().equals(c2.getLabel())) {
                return Result.unsafe("continue targets differ");
            }
            return Result.NONE;
        }
        return Result.NONE;
    }

    private static Result conditionResult(Expression c1, Expression c2, String construct) {
        if (c1.toString().equals(c2.toString())) {
            return Result.NONE;
        }
        return Result.parameterizable(construct + " condition differs: '" + c1 + "' vs '" + c2 + "'");
    }

    private static boolean sameText(NodeList<? extends com.github.javaparser.ast.Node> a,
            NodeList<? extends com.github.javaparser.ast.Node> b) {
        return a.toString().equals(b.toString());
    }

    static boolean isControlFlow(Statement s) {
        return s instanceof IfStmt || s instanceof WhileStmt || s instanceof DoStmt || s instanceof ForStmt
                || s instanceof ForEachStmt || s instanceof SwitchStmt || s instanceof TryStmt
                || s instanceof ReturnStmt || s instanceof BreakStmt || s instanceof ContinueStmt
                || s instanceof ThrowStmt || s instanceof LabeledStmt || s instanceof SynchronizedStmt;
    }
}
