package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.ScriptContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class TypeChecker {

    private static final Set<String> BUILTIN_TYPES = Set.of(
        "def", "int", "long", "float", "double", "boolean", "byte", "short", "char", "String", "void", "Object"
    );

    private final Ast.Source source;
    private final ScriptContext context;
    private final Map<String, Set<Integer>> functionArities = new HashMap<>();
    private final Deque<Set<String>> scopes = new ArrayDeque<>();
    private int loopDepth;

    public TypeChecker(Ast.Source source, ScriptContext context) {
        this.source = source;
        this.context = context;
    }

    public void check() {
        pushScope();
        if (context != null) {
            for (ScriptContext.Variable v : context.variables()) {
                declare(v.name());
            }
        }
        for (Ast.Stmt.FunctionDecl f : source.functions()) {
            functionArities.computeIfAbsent(f.name(), k -> new HashSet<>()).add(f.params().size());
        }
        for (Ast.Stmt.FunctionDecl f : source.functions()) {
            checkFunction(f);
        }
        for (Ast.Stmt s : source.statements()) {
            checkStmt(s);
        }
        popScope();
    }

    private void checkFunction(Ast.Stmt.FunctionDecl f) {
        checkTypeName(f.returnType());
        pushScope();
        for (Ast.Stmt.Param p : f.params()) {
            checkTypeName(p.type());
            declare(p.name());
        }
        checkStmt(f.body());
        popScope();
    }

    private void pushScope() {
        scopes.push(new HashSet<>());
    }

    private void popScope() {
        scopes.pop();
    }

    private void declare(String name) {
        scopes.peek().add(name);
    }

    private boolean isDeclared(String name) {
        for (Set<String> scope : scopes) {
            if (scope.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private void checkTypeName(String type) {
        if (type == null) {
            return;
        }
        String base = type;
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
        }
        if (BUILTIN_TYPES.contains(base)) {
            return;
        }
        if (!Sandbox.isTypeNameKnown(base)) {
            throw new PainlessTypeException("[" + base + "] is not whitelisted in a script context");
        }
    }

    private void checkStmt(Ast.Stmt stmt) {
        switch (stmt) {
            case Ast.Stmt.ExprStmt s -> checkExpr(s.expr());
            case Ast.Stmt.VarDecl s -> {
                checkTypeName(s.type());
                for (Ast.Stmt.DeclEntry d : s.decls()) {
                    if (d.init() != null) {
                        checkExpr(d.init());
                    }
                    declare(d.name());
                }
            }
            case Ast.Stmt.Block s -> {
                pushScope();
                for (Ast.Stmt st : s.statements()) {
                    checkStmt(st);
                }
                popScope();
            }
            case Ast.Stmt.If s -> {
                checkExpr(s.cond());
                checkStmt(s.thenStmt());
                if (s.elseStmt() != null) {
                    checkStmt(s.elseStmt());
                }
            }
            case Ast.Stmt.While s -> {
                checkExpr(s.cond());
                loopDepth++;
                checkStmt(s.body());
                loopDepth--;
            }
            case Ast.Stmt.DoWhile s -> {
                loopDepth++;
                checkStmt(s.body());
                loopDepth--;
                checkExpr(s.cond());
            }
            case Ast.Stmt.For s -> {
                pushScope();
                if (s.init() != null) {
                    checkStmt(s.init());
                }
                if (s.cond() != null) {
                    checkExpr(s.cond());
                }
                loopDepth++;
                checkStmt(s.body());
                loopDepth--;
                if (s.update() != null) {
                    checkStmt(s.update());
                }
                popScope();
            }
            case Ast.Stmt.ForEach s -> {
                checkExpr(s.iterable());
                checkTypeName(s.type());
                pushScope();
                declare(s.varName());
                loopDepth++;
                checkStmt(s.body());
                loopDepth--;
                popScope();
            }
            case Ast.Stmt.Break s -> {
                if (loopDepth == 0) {
                    throw new PainlessTypeException("break statement outside of a loop");
                }
            }
            case Ast.Stmt.Continue s -> {
                if (loopDepth == 0) {
                    throw new PainlessTypeException("continue statement outside of a loop");
                }
            }
            case Ast.Stmt.Return s -> {
                if (s.value() != null) {
                    checkExpr(s.value());
                }
            }
            case Ast.Stmt.TryCatch s -> {
                checkStmt(s.tryBlock());
                for (Ast.Stmt.CatchClause c : s.catches()) {
                    checkTypeName(c.exceptionType());
                    pushScope();
                    declare(c.varName());
                    checkStmt(c.body());
                    popScope();
                }
            }
            case Ast.Stmt.Throw s -> checkExpr(s.value());
            case Ast.Stmt.FunctionDecl s -> {
            }
        }
    }

    private void checkExpr(Ast.Expr expr) {
        switch (expr) {
            case Ast.Expr.NumberLit e -> {
            }
            case Ast.Expr.StringLit e -> {
            }
            case Ast.Expr.BoolLit e -> {
            }
            case Ast.Expr.NullLit e -> {
            }
            case Ast.Expr.RegexLit e -> {
            }
            case Ast.Expr.Name e -> {
                if (!isDeclared(e.name()) && !Sandbox.isTypeNameKnown(e.name()) && !e.name().equals("Debug")) {
                    throw new PainlessTypeException("Variable [" + e.name() + "] is not defined.");
                }
            }
            case Ast.Expr.ListInit e -> e.elements().forEach(this::checkExpr);
            case Ast.Expr.MapInit e -> e.entries().forEach(en -> {
                checkExpr(en.key());
                checkExpr(en.value());
            });
            case Ast.Expr.ArrayInit e -> {
                checkTypeName(e.elementType());
                e.elements().forEach(this::checkExpr);
            }
            case Ast.Expr.NewArray e -> {
                checkTypeName(e.elementType());
                e.dimensionSizes().forEach(this::checkExpr);
            }
            case Ast.Expr.Unary e -> checkExpr(e.operand());
            case Ast.Expr.Binary e -> {
                checkExpr(e.left());
                checkExpr(e.right());
            }
            case Ast.Expr.Logical e -> {
                checkExpr(e.left());
                checkExpr(e.right());
            }
            case Ast.Expr.Ternary e -> {
                checkExpr(e.cond());
                checkExpr(e.thenExpr());
                checkExpr(e.elseExpr());
            }
            case Ast.Expr.Elvis e -> {
                checkExpr(e.left());
                checkExpr(e.right());
            }
            case Ast.Expr.InstanceOf e -> {
                checkExpr(e.target());
                checkTypeName(e.type());
            }
            case Ast.Expr.Cast e -> {
                checkTypeName(e.type());
                checkExpr(e.target());
            }
            case Ast.Expr.Assign e -> {
                checkLValue(e.target());
                checkExpr(e.value());
            }
            case Ast.Expr.IncDec e -> checkLValue(e.target());
            case Ast.Expr.FieldAccess e -> checkExpr(e.target());
            case Ast.Expr.Index e -> {
                checkExpr(e.target());
                checkExpr(e.index());
            }
            case Ast.Expr.Call e -> {
                checkExpr(e.target());
                e.args().forEach(this::checkExpr);
            }
            case Ast.Expr.FunctionCall e -> {
                checkFunctionCallTarget(e.name(), e.args().size());
                e.args().forEach(this::checkExpr);
            }
            case Ast.Expr.NewObject e -> {
                checkTypeName(e.type());
                e.args().forEach(this::checkExpr);
            }
            case Ast.Expr.Lambda e -> {
                pushScope();
                e.params().forEach(this::declare);
                if (e.body() instanceof Ast.Stmt body) {
                    checkStmt(body);
                } else {
                    checkExpr((Ast.Expr) e.body());
                }
                popScope();
            }
            case Ast.Expr.MethodRef e -> {
                if (!isDeclared(e.qualifier()) && !Sandbox.isTypeNameKnown(e.qualifier())) {
                    throw new PainlessTypeException("Variable [" + e.qualifier() + "] is not defined.");
                }
            }
            case Ast.Expr.RegexMatch e -> {
                checkExpr(e.left());
                checkExpr(e.right());
            }
        }
    }

    private void checkFunctionCallTarget(String name, int arity) {
        Set<Integer> arities = functionArities.get(name);
        if (arities != null) {
            if (!arities.contains(arity)) {
                throw new PainlessTypeException("Unknown call [" + name + "] with [" + arity + "] arguments.");
            }
            return;
        }
        if (context != null && context.function(name, arity) != null) {
            return;
        }
        if (name.equals("emit")) {
            return;
        }
        throw new PainlessTypeException("Unknown call [" + name + "] with [" + arity + "] arguments.");
    }

    private void checkLValue(Ast.Expr target) {
        switch (target) {
            case Ast.Expr.Name n -> {
                if (!isDeclared(n.name())) {
                    throw new PainlessTypeException("Variable [" + n.name() + "] is not defined.");
                }
            }
            case Ast.Expr.FieldAccess f -> checkExpr(f.target());
            case Ast.Expr.Index idx -> {
                checkExpr(idx.target());
                checkExpr(idx.index());
            }
            default -> throw new PainlessTypeException("invalid assignment target");
        }
    }
}
