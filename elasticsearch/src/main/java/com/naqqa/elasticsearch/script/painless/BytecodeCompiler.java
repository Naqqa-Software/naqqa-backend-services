package com.naqqa.elasticsearch.script.painless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class BytecodeCompiler {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String OBJECT = "java/lang/Object";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String STRING_DESC = "Ljava/lang/String;";
    private static final PainlessClassLoader LOADER = new PainlessClassLoader(BytecodeCompiler.class.getClassLoader());

    private final ClassFileWriter.ConstantPool pool = new ClassFileWriter.ConstantPool();
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final Set<String> declaredNames = new LinkedHashSet<>();
    private final Deque<ClassFileWriter.Label[]> loopStack = new ArrayDeque<>();

    private BytecodeCompiler() {
    }

    public static boolean canCompile(Ast.Source source) {
        if (!source.functions().isEmpty()) {
            return false;
        }
        for (Ast.Stmt s : source.statements()) {
            if (!stmtSupported(s)) {
                return false;
            }
        }
        return true;
    }

    private static boolean stmtSupported(Ast.Stmt stmt) {
        return switch (stmt) {
            case Ast.Stmt.ExprStmt s -> exprSupported(s.expr());
            case Ast.Stmt.VarDecl s -> {
                if (s.type() != null && !s.type().equals("def")) {
                    yield false;
                }
                for (Ast.Stmt.DeclEntry d : s.decls()) {
                    if (d.init() != null && !exprSupported(d.init())) {
                        yield false;
                    }
                }
                yield true;
            }
            case Ast.Stmt.Block s -> s.statements().stream().allMatch(BytecodeCompiler::stmtSupported);
            case Ast.Stmt.If s -> exprSupported(s.cond()) && stmtSupported(s.thenStmt())
                && (s.elseStmt() == null || stmtSupported(s.elseStmt()));
            case Ast.Stmt.While s -> exprSupported(s.cond()) && stmtSupported(s.body());
            case Ast.Stmt.DoWhile s -> exprSupported(s.cond()) && stmtSupported(s.body());
            case Ast.Stmt.For s -> (s.init() == null || stmtSupported(s.init())) && (s.cond() == null || exprSupported(s.cond()))
                && (s.update() == null || stmtSupported(s.update())) && stmtSupported(s.body());
            case Ast.Stmt.Break s -> true;
            case Ast.Stmt.Continue s -> true;
            case Ast.Stmt.Return s -> s.value() == null || exprSupported(s.value());
            default -> false;
        };
    }

    private static boolean exprSupported(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Expr.NumberLit e -> true;
            case Ast.Expr.StringLit e -> true;
            case Ast.Expr.BoolLit e -> true;
            case Ast.Expr.NullLit e -> true;
            case Ast.Expr.Name e -> true;
            case Ast.Expr.Unary e -> exprSupported(e.operand());
            case Ast.Expr.Binary e -> exprSupported(e.left()) && exprSupported(e.right());
            case Ast.Expr.Logical e -> exprSupported(e.left()) && exprSupported(e.right());
            case Ast.Expr.Ternary e -> exprSupported(e.cond()) && exprSupported(e.thenExpr()) && exprSupported(e.elseExpr());
            case Ast.Expr.Elvis e -> exprSupported(e.left()) && exprSupported(e.right());
            case Ast.Expr.InstanceOf e -> exprSupported(e.target());
            case Ast.Expr.Cast e -> exprSupported(e.target());
            case Ast.Expr.Assign e -> e.target() instanceof Ast.Expr.Name && exprSupported(e.value());
            case Ast.Expr.IncDec e -> e.target() instanceof Ast.Expr.Name;
            case Ast.Expr.FieldAccess e -> !e.nullSafe() && !isTypeReference(e.target()) && exprSupported(e.target());
            case Ast.Expr.Index e -> !e.nullSafe() && exprSupported(e.target()) && exprSupported(e.index());
            case Ast.Expr.Call e -> !e.nullSafe() && !isTypeReference(e.target()) && exprSupported(e.target())
                && e.args().stream().allMatch(BytecodeCompiler::exprSupported);
            default -> false;
        };
    }

    private static boolean isTypeReference(Ast.Expr target) {
        return target instanceof Ast.Expr.Name n && (Sandbox.isTypeNameKnown(n.name()) || n.name().equals("Debug"));
    }

    public static synchronized CompiledResult compile(Ast.Source source, int maxLoopCounter) {
        BytecodeCompiler compiler = new BytecodeCompiler();
        return compiler.doCompile(source, maxLoopCounter);
    }

    public record CompiledResult(BytecodeExecutable executable) {}

    private CompiledResult doCompile(Ast.Source source, int maxLoopCounter) {
        collectNames(source.statements());
        int slot = 2;
        Set<String> freeNames = new LinkedHashSet<>();
        collectFreeNames(source.statements(), freeNames);
        for (String name : freeNames) {
            slots.put(name, slot++);
        }
        for (String name : declaredNames) {
            if (!slots.containsKey(name)) {
                slots.put(name, slot++);
            }
        }
        int maxLocals = slot;

        ClassFileWriter.CodeBuilder cb = new ClassFileWriter.CodeBuilder();
        cb.ldc(pool.integerConst(maxLoopCounter));
        invokeStaticOn(cb, "com/naqqa/elasticsearch/script/painless/LoopGuard", "reset", "(I)V", 1);
        int mapGet = pool.interfaceMethodref("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        for (Map.Entry<String, Integer> e : slots.entrySet()) {
            if (freeNames.contains(e.getKey())) {
                cb.aload(1);
                cb.ldc(pool.stringConst(e.getKey()));
                cb.invoke(ClassFileWriter.INVOKEINTERFACE, mapGet, 1, true, 2);
                cb.astore(e.getValue());
            } else {
                cb.aconstNull();
                cb.astore(e.getValue());
            }
        }

        compileTopLevel(source.statements(), cb);
        cb.aconstNull();
        cb.areturn();

        String className = "com/naqqa/elasticsearch/script/painless/generated/PainlessScript$" + COUNTER.incrementAndGet();
        int initRef = pool.methodref(OBJECT, "<init>", "()V");
        ClassFileWriter.CodeBuilder initCode = new ClassFileWriter.CodeBuilder();
        initCode.aload(0);
        initCode.invoke(ClassFileWriter.INVOKESPECIAL, initRef, 0, false, 0);
        initCode.vreturn();
        ClassFileWriter.MethodDef initMethod = new ClassFileWriter.MethodDef(0x1, "<init>", "()V", 1, initCode);
        ClassFileWriter.MethodDef execMethod = new ClassFileWriter.MethodDef(0x1, "exec", "(Ljava/util/Map;)Ljava/lang/Object;", maxLocals, cb);

        byte[] classBytes = ClassFileWriter.build(className, OBJECT, List.of("com/naqqa/elasticsearch/script/painless/BytecodeExecutable"),
            List.of(initMethod, execMethod), pool);
        Class<?> loaded = LOADER.define(className.replace('/', '.'), classBytes);
        try {
            BytecodeExecutable instance = (BytecodeExecutable) loaded.getDeclaredConstructor().newInstance();
            return new CompiledResult(instance);
        } catch (ReflectiveOperationException e) {
            throw new PainlessRuntimeError("failed to instantiate compiled script", e);
        }
    }

    private void collectNames(List<Ast.Stmt> stmts) {
        for (Ast.Stmt s : stmts) {
            collectDeclared(s);
        }
    }

    private void collectDeclared(Ast.Stmt s) {
        switch (s) {
            case Ast.Stmt.VarDecl v -> v.decls().forEach(d -> declaredNames.add(d.name()));
            case Ast.Stmt.Block b -> b.statements().forEach(this::collectDeclared);
            case Ast.Stmt.If i -> {
                collectDeclared(i.thenStmt());
                if (i.elseStmt() != null) {
                    collectDeclared(i.elseStmt());
                }
            }
            case Ast.Stmt.While w -> collectDeclared(w.body());
            case Ast.Stmt.DoWhile w -> collectDeclared(w.body());
            case Ast.Stmt.For f -> {
                if (f.init() != null) {
                    collectDeclared(f.init());
                }
                collectDeclared(f.body());
                if (f.update() != null) {
                    collectDeclared(f.update());
                }
            }
            default -> {
            }
        }
    }

    private void collectFreeNames(List<Ast.Stmt> stmts, Set<String> out) {
        for (Ast.Stmt s : stmts) {
            collectFreeInStmt(s, out);
        }
    }

    private void collectFreeInStmt(Ast.Stmt s, Set<String> out) {
        switch (s) {
            case Ast.Stmt.ExprStmt e -> collectFreeInExpr(e.expr(), out);
            case Ast.Stmt.VarDecl v -> v.decls().forEach(d -> {
                if (d.init() != null) {
                    collectFreeInExpr(d.init(), out);
                }
            });
            case Ast.Stmt.Block b -> b.statements().forEach(st -> collectFreeInStmt(st, out));
            case Ast.Stmt.If i -> {
                collectFreeInExpr(i.cond(), out);
                collectFreeInStmt(i.thenStmt(), out);
                if (i.elseStmt() != null) {
                    collectFreeInStmt(i.elseStmt(), out);
                }
            }
            case Ast.Stmt.While w -> {
                collectFreeInExpr(w.cond(), out);
                collectFreeInStmt(w.body(), out);
            }
            case Ast.Stmt.DoWhile w -> {
                collectFreeInExpr(w.cond(), out);
                collectFreeInStmt(w.body(), out);
            }
            case Ast.Stmt.For f -> {
                if (f.init() != null) {
                    collectFreeInStmt(f.init(), out);
                }
                if (f.cond() != null) {
                    collectFreeInExpr(f.cond(), out);
                }
                collectFreeInStmt(f.body(), out);
                if (f.update() != null) {
                    collectFreeInStmt(f.update(), out);
                }
            }
            case Ast.Stmt.Return r -> {
                if (r.value() != null) {
                    collectFreeInExpr(r.value(), out);
                }
            }
            default -> {
            }
        }
    }

    private void collectFreeInExpr(Ast.Expr e, Set<String> out) {
        switch (e) {
            case Ast.Expr.Name n -> {
                if (!declaredNames.contains(n.name())) {
                    out.add(n.name());
                }
            }
            case Ast.Expr.Unary u -> collectFreeInExpr(u.operand(), out);
            case Ast.Expr.Binary b -> {
                collectFreeInExpr(b.left(), out);
                collectFreeInExpr(b.right(), out);
            }
            case Ast.Expr.Logical b -> {
                collectFreeInExpr(b.left(), out);
                collectFreeInExpr(b.right(), out);
            }
            case Ast.Expr.Ternary t -> {
                collectFreeInExpr(t.cond(), out);
                collectFreeInExpr(t.thenExpr(), out);
                collectFreeInExpr(t.elseExpr(), out);
            }
            case Ast.Expr.Elvis t -> {
                collectFreeInExpr(t.left(), out);
                collectFreeInExpr(t.right(), out);
            }
            case Ast.Expr.InstanceOf i -> collectFreeInExpr(i.target(), out);
            case Ast.Expr.Cast c -> collectFreeInExpr(c.target(), out);
            case Ast.Expr.Assign a -> {
                collectFreeInExpr(a.target(), out);
                collectFreeInExpr(a.value(), out);
            }
            case Ast.Expr.IncDec i -> collectFreeInExpr(i.target(), out);
            case Ast.Expr.FieldAccess f -> collectFreeInExpr(f.target(), out);
            case Ast.Expr.Index i -> {
                collectFreeInExpr(i.target(), out);
                collectFreeInExpr(i.index(), out);
            }
            case Ast.Expr.Call c -> {
                collectFreeInExpr(c.target(), out);
                c.args().forEach(a -> collectFreeInExpr(a, out));
            }
            default -> {
            }
        }
    }

    private void compileTopLevel(List<Ast.Stmt> stmts, ClassFileWriter.CodeBuilder cb) {
        for (int i = 0; i < stmts.size(); i++) {
            Ast.Stmt s = stmts.get(i);
            if (i == stmts.size() - 1 && s instanceof Ast.Stmt.ExprStmt es) {
                compileExpr(es.expr(), cb);
                cb.areturn();
            } else {
                compileStmt(s, cb);
            }
        }
    }

    private void compileStmt(Ast.Stmt stmt, ClassFileWriter.CodeBuilder cb) {
        switch (stmt) {
            case Ast.Stmt.ExprStmt s -> {
                compileExpr(s.expr(), cb);
                cb.pop();
            }
            case Ast.Stmt.VarDecl s -> {
                for (Ast.Stmt.DeclEntry d : s.decls()) {
                    if (d.init() != null) {
                        compileExpr(d.init(), cb);
                    } else {
                        cb.aconstNull();
                    }
                    cb.astore(slots.get(d.name()));
                }
            }
            case Ast.Stmt.Block s -> s.statements().forEach(st -> compileStmt(st, cb));
            case Ast.Stmt.If s -> {
                ClassFileWriter.Label elseLabel = cb.newLabel();
                compileExpr(s.cond(), cb);
                invokeTruthy(cb);
                cb.ifeq(elseLabel);
                compileStmt(s.thenStmt(), cb);
                if (s.elseStmt() != null) {
                    ClassFileWriter.Label endLabel = cb.newLabel();
                    cb.gotoLabel(endLabel);
                    cb.mark(elseLabel);
                    compileStmt(s.elseStmt(), cb);
                    cb.mark(endLabel);
                } else {
                    cb.mark(elseLabel);
                }
            }
            case Ast.Stmt.While s -> {
                ClassFileWriter.Label start = cb.newLabel();
                ClassFileWriter.Label end = cb.newLabel();
                cb.mark(start);
                compileExpr(s.cond(), cb);
                invokeTruthy(cb);
                cb.ifeq(end);
                tick(cb);
                loopStack.push(new ClassFileWriter.Label[]{start, end});
                compileStmt(s.body(), cb);
                loopStack.pop();
                cb.gotoLabel(start);
                cb.mark(end);
            }
            case Ast.Stmt.DoWhile s -> {
                ClassFileWriter.Label bodyStart = cb.newLabel();
                ClassFileWriter.Label condLabel = cb.newLabel();
                ClassFileWriter.Label end = cb.newLabel();
                cb.mark(bodyStart);
                tick(cb);
                loopStack.push(new ClassFileWriter.Label[]{condLabel, end});
                compileStmt(s.body(), cb);
                loopStack.pop();
                cb.mark(condLabel);
                compileExpr(s.cond(), cb);
                invokeTruthy(cb);
                cb.ifne(bodyStart);
                cb.mark(end);
            }
            case Ast.Stmt.For s -> {
                if (s.init() != null) {
                    compileStmt(s.init(), cb);
                }
                ClassFileWriter.Label condLabel = cb.newLabel();
                ClassFileWriter.Label updateLabel = cb.newLabel();
                ClassFileWriter.Label end = cb.newLabel();
                cb.mark(condLabel);
                if (s.cond() != null) {
                    compileExpr(s.cond(), cb);
                    invokeTruthy(cb);
                    cb.ifeq(end);
                }
                tick(cb);
                loopStack.push(new ClassFileWriter.Label[]{updateLabel, end});
                compileStmt(s.body(), cb);
                loopStack.pop();
                cb.mark(updateLabel);
                if (s.update() != null) {
                    compileStmt(s.update(), cb);
                }
                cb.gotoLabel(condLabel);
                cb.mark(end);
            }
            case Ast.Stmt.Break s -> cb.gotoLabel(loopStack.peek()[1]);
            case Ast.Stmt.Continue s -> cb.gotoLabel(loopStack.peek()[0]);
            case Ast.Stmt.Return s -> {
                if (s.value() != null) {
                    compileExpr(s.value(), cb);
                } else {
                    cb.aconstNull();
                }
                cb.areturn();
            }
            default -> throw new PainlessRuntimeError("unsupported statement in bytecode compiler");
        }
    }

    private void tick(ClassFileWriter.CodeBuilder cb) {
        invokeStaticOn(cb, "com/naqqa/elasticsearch/script/painless/LoopGuard", "tick", "()V", 0);
    }

    private void invokeTruthy(ClassFileWriter.CodeBuilder cb) {
        int ref = pool.methodref("com/naqqa/elasticsearch/script/painless/PainlessOps", "truthy", "(Ljava/lang/Object;)Z");
        cb.invoke(ClassFileWriter.INVOKESTATIC, ref, 1, true, 0);
    }

    private void compileExpr(Ast.Expr expr, ClassFileWriter.CodeBuilder cb) {
        switch (expr) {
            case Ast.Expr.NumberLit e -> pushConstant(e.value(), cb);
            case Ast.Expr.StringLit e -> cb.ldc(pool.stringConst(e.value()));
            case Ast.Expr.BoolLit e -> pushConstant(e.value(), cb);
            case Ast.Expr.NullLit e -> cb.aconstNull();
            case Ast.Expr.Name e -> cb.aload(slots.get(e.name()));
            case Ast.Expr.Unary e -> {
                compileExpr(e.operand(), cb);
                cb.ldc(pool.stringConst(e.op()));
                invokeStatic(cb, "unaryOp", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 2);
            }
            case Ast.Expr.Binary e -> {
                compileExpr(e.left(), cb);
                compileExpr(e.right(), cb);
                cb.ldc(pool.stringConst(e.op()));
                invokeStatic(cb, "binaryOp", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 3);
            }
            case Ast.Expr.Logical e -> compileLogical(e, cb);
            case Ast.Expr.Ternary e -> {
                ClassFileWriter.Label elseLabel = cb.newLabel();
                ClassFileWriter.Label end = cb.newLabel();
                compileExpr(e.cond(), cb);
                invokeTruthy(cb);
                cb.ifeq(elseLabel);
                compileExpr(e.thenExpr(), cb);
                cb.gotoLabel(end);
                cb.mark(elseLabel);
                compileExpr(e.elseExpr(), cb);
                cb.mark(end);
            }
            case Ast.Expr.Elvis e -> {
                ClassFileWriter.Label end = cb.newLabel();
                compileExpr(e.left(), cb);
                cb.dup();
                cb.ifnonnull(end);
                cb.pop();
                compileExpr(e.right(), cb);
                cb.mark(end);
            }
            case Ast.Expr.InstanceOf e -> {
                cb.ldc(pool.stringConst(e.type()));
                compileExpr(e.target(), cb);
                invokeStatic(cb, "isInstanceObj", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Boolean;", 2);
            }
            case Ast.Expr.Cast e -> {
                cb.ldc(pool.stringConst(e.type()));
                compileExpr(e.target(), cb);
                invokeStatic(cb, "castTo", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", 2);
            }
            case Ast.Expr.Assign e -> compileAssign(e, cb);
            case Ast.Expr.IncDec e -> compileIncDec(e, cb);
            case Ast.Expr.FieldAccess e -> {
                compileExpr(e.target(), cb);
                cb.ldc(pool.stringConst(e.name()));
                invokeStatic(cb, "getProperty", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 2);
            }
            case Ast.Expr.Index e -> {
                compileExpr(e.target(), cb);
                compileExpr(e.index(), cb);
                invokeStatic(cb, "index", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2);
            }
            case Ast.Expr.Call e -> {
                compileExpr(e.target(), cb);
                cb.ldc(pool.stringConst(e.name()));
                compileArgsArray(e.args(), cb);
                invokeStatic(cb, "callMethod", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", 3);
            }
            default -> throw new PainlessRuntimeError("unsupported expression in bytecode compiler");
        }
    }

    private void compileArgsArray(List<Ast.Expr> args, ClassFileWriter.CodeBuilder cb) {
        int objectClass = pool.classRef("java/lang/Object");
        cb.ldc(pool.integerConst(args.size()));
        cb.anewarray(objectClass);
        for (int i = 0; i < args.size(); i++) {
            cb.dup();
            cb.ldc(pool.integerConst(i));
            compileExpr(args.get(i), cb);
            cb.aastore();
        }
    }

    private void compileLogical(Ast.Expr.Logical e, ClassFileWriter.CodeBuilder cb) {
        ClassFileWriter.Label shortCircuit = cb.newLabel();
        ClassFileWriter.Label end = cb.newLabel();
        compileExpr(e.left(), cb);
        invokeTruthy(cb);
        if (e.op().equals("&&")) {
            cb.ifeq(shortCircuit);
            compileExpr(e.right(), cb);
            invokeTruthy(cb);
            cb.gotoLabel(end);
            cb.mark(shortCircuit);
            cb.iconst(0);
            cb.mark(end);
        } else {
            cb.ifne(shortCircuit);
            compileExpr(e.right(), cb);
            invokeTruthy(cb);
            cb.gotoLabel(end);
            cb.mark(shortCircuit);
            cb.iconst(1);
            cb.mark(end);
        }
        boxBoolean(cb);
    }

    private void compileAssign(Ast.Expr.Assign e, ClassFileWriter.CodeBuilder cb) {
        Ast.Expr.Name target = (Ast.Expr.Name) e.target();
        int slot = slots.get(target.name());
        if (e.op().equals("=")) {
            compileExpr(e.value(), cb);
        } else {
            cb.aload(slot);
            compileExpr(e.value(), cb);
            cb.ldc(pool.stringConst(e.op().substring(0, e.op().length() - 1)));
            invokeStatic(cb, "binaryOp", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 3);
        }
        cb.dup();
        cb.astore(slot);
    }

    private void compileIncDec(Ast.Expr.IncDec e, ClassFileWriter.CodeBuilder cb) {
        Ast.Expr.Name target = (Ast.Expr.Name) e.target();
        int slot = slots.get(target.name());
        String op = e.increment() ? "+" : "-";
        int oneRef = pool.integerConst(1);
        if (e.prefix()) {
            cb.aload(slot);
            cb.ldc(oneRef);
            boxInt(cb);
            cb.ldc(pool.stringConst(op));
            invokeStatic(cb, "binaryOp", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 3);
            cb.dup();
            cb.astore(slot);
        } else {
            cb.aload(slot);
            cb.dup();
            cb.ldc(oneRef);
            boxInt(cb);
            cb.ldc(pool.stringConst(op));
            invokeStatic(cb, "binaryOp", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 3);
            cb.astore(slot);
        }
    }

    private void pushConstant(Object value, ClassFileWriter.CodeBuilder cb) {
        if (value instanceof Boolean b) {
            cb.iconst(b ? 1 : 0);
            boxBoolean(cb);
        } else if (value instanceof Integer i) {
            cb.ldc(pool.integerConst(i));
            invokeStaticOn(cb, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", 1);
        } else if (value instanceof Long l) {
            cb.ldc2(pool.longConst(l));
            invokeStaticOn(cb, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", 1);
        } else if (value instanceof Float f) {
            cb.ldc(pool.floatConst(f));
            invokeStaticOn(cb, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", 1);
        } else if (value instanceof Double d) {
            cb.ldc2(pool.doubleConst(d));
            invokeStaticOn(cb, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", 1);
        } else {
            throw new PainlessRuntimeError("unsupported constant type in bytecode compiler");
        }
    }

    private void boxBoolean(ClassFileWriter.CodeBuilder cb) {
        invokeStaticOn(cb, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", 1);
    }

    private void boxInt(ClassFileWriter.CodeBuilder cb) {
        invokeStaticOn(cb, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", 1);
    }

    private void invokeStatic(ClassFileWriter.CodeBuilder cb, String name, String desc, int argCount) {
        invokeStaticOn(cb, "com/naqqa/elasticsearch/script/painless/PainlessOps", name, desc, argCount);
    }

    private void invokeStaticOn(ClassFileWriter.CodeBuilder cb, String owner, String name, String desc, int argCount) {
        int ref = pool.methodref(owner, name, desc);
        cb.invoke(ClassFileWriter.INVOKESTATIC, ref, argCount, true, 0);
    }
}
