package com.naqqa.elasticsearch.script.painless;

import java.util.List;

public final class Ast {

    private Ast() {
    }

    public sealed interface Expr {
        int pos();

        record NumberLit(Object value, int pos) implements Expr {}
        record StringLit(String value, int pos) implements Expr {}
        record RegexLit(String pattern, String flags, int pos) implements Expr {}
        record BoolLit(boolean value, int pos) implements Expr {}
        record NullLit(int pos) implements Expr {}
        record Name(String name, int pos) implements Expr {}
        record ListInit(List<Expr> elements, int pos) implements Expr {}
        record MapEntry(Expr key, Expr value) {}
        record MapInit(List<MapEntry> entries, int pos) implements Expr {}
        record ArrayInit(String elementType, List<Expr> elements, int pos) implements Expr {}
        record NewArray(String elementType, List<Expr> dimensionSizes, int extraDims, int pos) implements Expr {}
        record Unary(String op, Expr operand, int pos) implements Expr {}
        record Binary(String op, Expr left, Expr right, int pos) implements Expr {}
        record Logical(String op, Expr left, Expr right, int pos) implements Expr {}
        record Ternary(Expr cond, Expr thenExpr, Expr elseExpr, int pos) implements Expr {}
        record Elvis(Expr left, Expr right, int pos) implements Expr {}
        record InstanceOf(Expr target, String type, int pos) implements Expr {}
        record Cast(String type, Expr target, int pos) implements Expr {}
        record Assign(Expr target, String op, Expr value, int pos) implements Expr {}
        record IncDec(Expr target, boolean increment, boolean prefix, int pos) implements Expr {}
        record FieldAccess(Expr target, String name, boolean nullSafe, int pos) implements Expr {}
        record Index(Expr target, Expr index, boolean nullSafe, int pos) implements Expr {}
        record Call(Expr target, String name, List<Expr> args, boolean nullSafe, int pos) implements Expr {}
        record FunctionCall(String name, List<Expr> args, int pos) implements Expr {}
        record NewObject(String type, List<Expr> args, int pos) implements Expr {}
        record Lambda(List<String> params, Object body, int pos) implements Expr {}
        record MethodRef(String qualifier, String method, int pos) implements Expr {}
        record RegexMatch(Expr left, Expr right, boolean fullMatch, int pos) implements Expr {}
    }

    public sealed interface Stmt {
        record DeclEntry(String name, Expr init) {}
        record ExprStmt(Expr expr) implements Stmt {}
        record VarDecl(String type, List<DeclEntry> decls) implements Stmt {}
        record Block(List<Stmt> statements) implements Stmt {}
        record If(Expr cond, Stmt thenStmt, Stmt elseStmt) implements Stmt {}
        record While(Expr cond, Stmt body) implements Stmt {}
        record DoWhile(Stmt body, Expr cond) implements Stmt {}
        record For(Stmt init, Expr cond, Stmt update, Stmt body) implements Stmt {}
        record ForEach(String type, String varName, Expr iterable, Stmt body) implements Stmt {}
        record Break() implements Stmt {}
        record Continue() implements Stmt {}
        record Return(Expr value) implements Stmt {}
        record CatchClause(String exceptionType, String varName, Stmt body) {}
        record TryCatch(Stmt tryBlock, List<CatchClause> catches) implements Stmt {}
        record Throw(Expr value) implements Stmt {}
        record Param(String type, String name) {}
        record FunctionDecl(String returnType, String name, List<Param> params, Stmt body) implements Stmt {}
    }

    public record Source(List<Stmt.FunctionDecl> functions, List<Stmt> statements) {}
}
