package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.Emitter;
import com.naqqa.elasticsearch.script.FunctionContext;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptFunction;
import com.naqqa.elasticsearch.script.ScriptSettings;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Interpreter {

    private final Ast.Source source;
    private final ScriptContext context;
    private final ScriptSettings settings;
    private final Map<String, Ast.Stmt.FunctionDecl> functions = new LinkedHashMap<>();
    private int loopCounter;
    private Emitter emitter;

    public Interpreter(Ast.Source source, ScriptContext context, ScriptSettings settings) {
        this.source = source;
        this.context = context;
        this.settings = settings == null ? ScriptSettings.defaults() : settings;
        for (Ast.Stmt.FunctionDecl f : source.functions()) {
            functions.put(f.name() + "/" + f.params().size(), f);
        }
    }

    public Object execute(Map<String, Object> vars, Emitter emitter) {
        this.loopCounter = 0;
        this.emitter = emitter;
        Environment env = new Environment(null);
        if (vars != null) {
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                env.define(e.getKey(), "def", e.getValue());
            }
        }
        try {
            return execTopLevel(source.statements(), env);
        } catch (ReturnSignal r) {
            return r.value;
        }
    }

    private Object execTopLevel(List<Ast.Stmt> stmts, Environment env) {
        Object last = null;
        for (int i = 0; i < stmts.size(); i++) {
            Ast.Stmt s = stmts.get(i);
            if (i == stmts.size() - 1 && s instanceof Ast.Stmt.ExprStmt es) {
                last = eval(es.expr(), env);
            } else {
                exec(s, env);
            }
        }
        return last;
    }

    private void execAll(List<Ast.Stmt> stmts, Environment env) {
        for (Ast.Stmt s : stmts) {
            exec(s, env);
        }
    }

    private void checkLoopLimit() {
        loopCounter++;
        if (loopCounter > settings.maxLoopCounter()) {
            throw new PainlessRuntimeError("The maximum number of statements that can be executed in a loop has been reached: " + settings.maxLoopCounter());
        }
    }

    private void exec(Ast.Stmt stmt, Environment env) {
        switch (stmt) {
            case Ast.Stmt.ExprStmt s -> eval(s.expr(), env);
            case Ast.Stmt.VarDecl s -> {
                for (Ast.Stmt.DeclEntry d : s.decls()) {
                    Object value = d.init() == null ? defaultValueFor(s.type()) : eval(d.init(), env);
                    env.define(d.name(), s.type(), value);
                }
            }
            case Ast.Stmt.Block s -> execAll(s.statements(), env.child());
            case Ast.Stmt.If s -> {
                if (PainlessOps.truthy(eval(s.cond(), env))) {
                    exec(s.thenStmt(), env);
                } else if (s.elseStmt() != null) {
                    exec(s.elseStmt(), env);
                }
            }
            case Ast.Stmt.While s -> {
                while (PainlessOps.truthy(eval(s.cond(), env))) {
                    checkLoopLimit();
                    try {
                        exec(s.body(), env);
                    } catch (BreakSignal b) {
                        break;
                    } catch (ContinueSignal c) {
                    }
                }
            }
            case Ast.Stmt.DoWhile s -> {
                do {
                    checkLoopLimit();
                    try {
                        exec(s.body(), env);
                    } catch (BreakSignal b) {
                        break;
                    } catch (ContinueSignal c) {
                    }
                } while (PainlessOps.truthy(eval(s.cond(), env)));
            }
            case Ast.Stmt.For s -> {
                Environment forEnv = env.child();
                if (s.init() != null) {
                    exec(s.init(), forEnv);
                }
                while (s.cond() == null || PainlessOps.truthy(eval(s.cond(), forEnv))) {
                    checkLoopLimit();
                    try {
                        exec(s.body(), forEnv);
                    } catch (BreakSignal b) {
                        break;
                    } catch (ContinueSignal c) {
                    }
                    if (s.update() != null) {
                        exec(s.update(), forEnv);
                    }
                }
            }
            case Ast.Stmt.ForEach s -> {
                Object iterable = eval(s.iterable(), env);
                for (Object item : toIterable(iterable)) {
                    checkLoopLimit();
                    Environment iterEnv = env.child();
                    iterEnv.define(s.varName(), s.type(), item);
                    try {
                        exec(s.body(), iterEnv);
                    } catch (BreakSignal b) {
                        break;
                    } catch (ContinueSignal c) {
                    }
                }
            }
            case Ast.Stmt.Break s -> throw BreakSignal.INSTANCE;
            case Ast.Stmt.Continue s -> throw ContinueSignal.INSTANCE;
            case Ast.Stmt.Return s -> throw new ReturnSignal(s.value() == null ? null : eval(s.value(), env));
            case Ast.Stmt.TryCatch s -> execTryCatch(s, env);
            case Ast.Stmt.Throw s -> {
                Object value = eval(s.value(), env);
                if (value instanceof RuntimeException re) {
                    throw re;
                }
                throw new PainlessRuntimeError(String.valueOf(value));
            }
            case Ast.Stmt.FunctionDecl s -> {
            }
        }
    }

    private void execTryCatch(Ast.Stmt.TryCatch s, Environment env) {
        try {
            exec(s.tryBlock(), env.child());
        } catch (BreakSignal | ContinueSignal | ReturnSignal control) {
            throw control;
        } catch (RuntimeException ex) {
            for (Ast.Stmt.CatchClause clause : s.catches()) {
                if (matchesCatchType(clause.exceptionType(), ex)) {
                    Environment catchEnv = env.child();
                    catchEnv.define(clause.varName(), "def", ex);
                    exec(clause.body(), catchEnv);
                    return;
                }
            }
            throw ex;
        }
    }

    private boolean matchesCatchType(String type, RuntimeException ex) {
        if (type.equals("Exception") || type.equals("RuntimeException") || type.equals("Throwable")) {
            return true;
        }
        if (!Sandbox.isTypeNameKnown(type)) {
            return true;
        }
        return Sandbox.resolveTypeName(type).isInstance(ex);
    }

    private Object defaultValueFor(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0f;
            case "double" -> 0d;
            case "boolean" -> false;
            case "byte" -> (byte) 0;
            case "short" -> (short) 0;
            case "char" -> (char) 0;
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private Iterable<Object> toIterable(Object value) {
        if (value == null) {
            throw new NullPointerException("cannot iterate a null value");
        }
        if (value instanceof Map<?, ?> m) {
            return (Iterable<Object>) (Iterable<?>) m.values();
        }
        if (value instanceof Iterable<?> it) {
            return (Iterable<Object>) it;
        }
        if (value.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                list.add(Array.get(value, i));
            }
            return list;
        }
        throw new ClassCastException("Cannot iterate over [" + PainlessOps.typeName(value) + "]");
    }

    Object eval(Ast.Expr expr, Environment env) {
        return switch (expr) {
            case Ast.Expr.NumberLit e -> e.value();
            case Ast.Expr.StringLit e -> e.value();
            case Ast.Expr.BoolLit e -> e.value();
            case Ast.Expr.NullLit e -> null;
            case Ast.Expr.RegexLit e -> compileRegex(e.pattern(), e.flags());
            case Ast.Expr.Name e -> env.get(e.name());
            case Ast.Expr.ListInit e -> {
                List<Object> list = new ArrayList<>();
                for (Ast.Expr el : e.elements()) {
                    list.add(eval(el, env));
                }
                yield list;
            }
            case Ast.Expr.MapInit e -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                for (Ast.Expr.MapEntry en : e.entries()) {
                    map.put(eval(en.key(), env), eval(en.value(), env));
                }
                yield map;
            }
            case Ast.Expr.ArrayInit e -> {
                Class<?> component = resolveComponentClass(e.elementType());
                Object array = Array.newInstance(component, e.elements().size());
                for (int i = 0; i < e.elements().size(); i++) {
                    Array.set(array, i, PainlessOps.castTo(e.elementType(), eval(e.elements().get(i), env)));
                }
                yield array;
            }
            case Ast.Expr.NewArray e -> {
                Class<?> component = resolveComponentClass(e.elementType());
                int[] dims = new int[e.dimensionSizes().size() + e.extraDims()];
                for (int i = 0; i < e.dimensionSizes().size(); i++) {
                    dims[i] = PainlessOps.toInt(eval(e.dimensionSizes().get(i), env));
                }
                yield dims.length <= 1 ? Array.newInstance(component, dims.length == 0 ? 0 : dims[0]) : Array.newInstance(component, dims);
            }
            case Ast.Expr.Unary e -> PainlessOps.unaryOp(eval(e.operand(), env), e.op());
            case Ast.Expr.Binary e -> PainlessOps.binaryOp(eval(e.left(), env), eval(e.right(), env), e.op());
            case Ast.Expr.Logical e -> e.op().equals("&&")
                ? (PainlessOps.truthy(eval(e.left(), env)) && PainlessOps.truthy(eval(e.right(), env)))
                : (PainlessOps.truthy(eval(e.left(), env)) || PainlessOps.truthy(eval(e.right(), env)));
            case Ast.Expr.Ternary e -> PainlessOps.truthy(eval(e.cond(), env)) ? eval(e.thenExpr(), env) : eval(e.elseExpr(), env);
            case Ast.Expr.Elvis e -> {
                Object l = eval(e.left(), env);
                yield l != null ? l : eval(e.right(), env);
            }
            case Ast.Expr.InstanceOf e -> PainlessOps.isInstance(e.type(), eval(e.target(), env));
            case Ast.Expr.Cast e -> PainlessOps.castTo(e.type(), eval(e.target(), env));
            case Ast.Expr.Assign e -> evalAssign(e, env);
            case Ast.Expr.IncDec e -> evalIncDec(e, env);
            case Ast.Expr.FieldAccess e -> evalFieldAccess(e, env);
            case Ast.Expr.Index e -> {
                Object t = eval(e.target(), env);
                if (e.nullSafe() && t == null) {
                    yield null;
                }
                yield PainlessOps.index(t, eval(e.index(), env));
            }
            case Ast.Expr.Call e -> evalCall(e, env);
            case Ast.Expr.FunctionCall e -> evalFunctionCall(e, env);
            case Ast.Expr.NewObject e -> {
                Class<?> cls = Sandbox.resolveTypeName(e.type());
                yield PainlessOps.instantiate(cls, evalArgs(e.args(), env));
            }
            case Ast.Expr.Lambda e -> makeLambda(e, env);
            case Ast.Expr.MethodRef e -> makeMethodRef(e, env);
            case Ast.Expr.RegexMatch e -> evalRegexMatch(e, env);
        };
    }

    private Class<?> resolveComponentClass(String type) {
        return switch (type) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "String" -> String.class;
            case "def", "Object" -> Object.class;
            default -> Sandbox.resolveTypeName(type);
        };
    }

    private Object evalAssign(Ast.Expr.Assign e, Environment env) {
        Object rhs = eval(e.value(), env);
        if (!e.op().equals("=")) {
            Object current = eval(e.target(), env);
            String binOp = e.op().substring(0, e.op().length() - 1);
            rhs = PainlessOps.binaryOp(current, rhs, binOp);
        }
        assignTo(e.target(), rhs, env);
        return rhs;
    }

    private void assignTo(Ast.Expr target, Object value, Environment env) {
        switch (target) {
            case Ast.Expr.Name n -> env.assign(n.name(), value);
            case Ast.Expr.FieldAccess f -> {
                Object t = eval(f.target(), env);
                if (t instanceof Map<?, ?> m) {
                    setMapEntry(m, f.name(), value);
                } else {
                    setField(t, f.name(), value);
                }
            }
            case Ast.Expr.Index idx -> {
                Object t = eval(idx.target(), env);
                PainlessOps.indexSet(t, eval(idx.index(), env), value);
            }
            default -> throw new PainlessRuntimeError("invalid assignment target");
        }
    }

    @SuppressWarnings("unchecked")
    private void setMapEntry(Map<?, ?> m, String key, Object value) {
        ((Map<Object, Object>) m).put(key, value);
    }

    private void setField(Object target, String name, Object value) {
        Sandbox.checkClass(target.getClass());
        try {
            java.lang.reflect.Field field = target.getClass().getField(name);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new PainlessSecurityException("cannot assign field [" + name + "]: " + e.getMessage());
        }
    }

    private Object evalIncDec(Ast.Expr.IncDec e, Environment env) {
        Object current = eval(e.target(), env);
        Object updated = e.increment() ? PainlessOps.add(current, 1) : PainlessOps.sub(current, 1);
        assignTo(e.target(), updated, env);
        return e.prefix() ? updated : current;
    }

    private Object evalFieldAccess(Ast.Expr.FieldAccess e, Environment env) {
        if (e.target() instanceof Ast.Expr.Name n && !env.has(n.name()) && Sandbox.isTypeNameKnown(n.name())) {
            return PainlessOps.getStaticProperty(Sandbox.resolveTypeName(n.name()), e.name());
        }
        Object t = eval(e.target(), env);
        if (e.nullSafe() && t == null) {
            return null;
        }
        return PainlessOps.getProperty(t, e.name());
    }

    private Object[] evalArgs(List<Ast.Expr> args, Environment env) {
        Object[] out = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            out[i] = eval(args.get(i), env);
        }
        return out;
    }

    private Object evalCall(Ast.Expr.Call e, Environment env) {
        if (e.target() instanceof Ast.Expr.Name n && !env.has(n.name())) {
            if (n.name().equals("Debug") && e.name().equals("explain")) {
                throw new PainlessExplainException(eval(e.args().get(0), env));
            }
            if (Sandbox.isTypeNameKnown(n.name())) {
                return PainlessOps.callStatic(Sandbox.resolveTypeName(n.name()), e.name(), evalArgs(e.args(), env));
            }
        }
        Object t = eval(e.target(), env);
        if (e.nullSafe() && t == null) {
            return null;
        }
        return PainlessOps.callMethod(t, e.name(), evalArgs(e.args(), env));
    }

    private Object evalFunctionCall(Ast.Expr.FunctionCall e, Environment env) {
        Ast.Stmt.FunctionDecl fn = functions.get(e.name() + "/" + e.args().size());
        if (fn != null) {
            return callUserFunction(fn, evalArgs(e.args(), env));
        }
        if (context != null) {
            ScriptFunction sf = context.function(e.name(), e.args().size());
            if (sf != null) {
                FunctionContext fc = makeFunctionContext(env);
                return sf.call(fc, evalArgs(e.args(), env));
            }
        }
        if (e.name().equals("emit") && emitter != null) {
            for (Object arg : evalArgs(e.args(), env)) {
                emitter.emit(arg);
            }
            return null;
        }
        throw new PainlessRuntimeError("Unknown call [" + e.name() + "] with [" + e.args().size() + "] arguments.");
    }

    private FunctionContext makeFunctionContext(Environment env) {
        return new FunctionContext() {
            @Override
            public Object variable(String name) {
                return env.has(name) ? env.get(name) : null;
            }

            @Override
            public void emit(Object value) {
                if (emitter != null) {
                    emitter.emit(value);
                }
            }
        };
    }

    private Object callUserFunction(Ast.Stmt.FunctionDecl fn, Object[] args) {
        Environment fnEnv = new Environment(null);
        for (int i = 0; i < fn.params().size(); i++) {
            Ast.Stmt.Param p = fn.params().get(i);
            fnEnv.define(p.name(), p.type(), args[i]);
        }
        try {
            exec(fn.body(), fnEnv);
            return null;
        } catch (ReturnSignal r) {
            return fn.returnType() == null || fn.returnType().equals("void") || fn.returnType().equals("def")
                ? r.value : PainlessOps.castTo(fn.returnType(), r.value);
        }
    }

    private Object makeLambda(Ast.Expr.Lambda e, Environment env) {
        return new PainlessLambda(e.params().size(), args -> {
            Environment lenv = env.child();
            for (int i = 0; i < e.params().size(); i++) {
                lenv.define(e.params().get(i), "def", i < args.length ? args[i] : null);
            }
            if (e.body() instanceof Ast.Stmt block) {
                try {
                    exec(block, lenv);
                    return null;
                } catch (ReturnSignal r) {
                    return r.value;
                }
            }
            return eval((Ast.Expr) e.body(), lenv);
        });
    }

    private Object makeMethodRef(Ast.Expr.MethodRef e, Environment env) {
        if (env.has(e.qualifier())) {
            Object instance = env.get(e.qualifier());
            return new PainlessLambda(-1, args -> PainlessOps.callMethod(instance, e.method(), args));
        }
        if (Sandbox.isTypeNameKnown(e.qualifier())) {
            Class<?> cls = Sandbox.resolveTypeName(e.qualifier());
            if (e.method().equals("new")) {
                return new PainlessLambda(-1, args -> PainlessOps.instantiate(cls, args));
            }
            return new PainlessLambda(-1, args -> {
                if (args.length > 0) {
                    try {
                        return PainlessOps.callStatic(cls, e.method(), args);
                    } catch (RuntimeException ex) {
                        Object[] rest = new Object[args.length - 1];
                        System.arraycopy(args, 1, rest, 0, rest.length);
                        return PainlessOps.callMethod(args[0], e.method(), rest);
                    }
                }
                return PainlessOps.callStatic(cls, e.method(), args);
            });
        }
        throw new PainlessRuntimeError("Variable [" + e.qualifier() + "] is not defined.");
    }

    private Object evalRegexMatch(Ast.Expr.RegexMatch e, Environment env) {
        Object left = eval(e.left(), env);
        Object right = eval(e.right(), env);
        String s = String.valueOf(left);
        Pattern pattern = right instanceof Pattern p ? p : compileRegex(String.valueOf(right), "");
        checkRegexLimit(s, pattern);
        Matcher m = pattern.matcher(s);
        return e.fullMatch() ? m.matches() : m.find();
    }

    private void checkRegexLimit(String input, Pattern pattern) {
        if (settings.regexMode() == ScriptSettings.RegexMode.LIMITED) {
            int max = Math.max(1000, pattern.pattern().length() * settings.regexLimitFactor());
            if (input.length() > max) {
                throw new PainlessSecurityException("[" + input.length() + "] chars exceeds regex limit factor of [" + settings.regexLimitFactor() + "]");
            }
        }
    }

    private Pattern compileRegex(String pattern, String flags) {
        if (settings.regexMode() == ScriptSettings.RegexMode.DISABLED) {
            throw new PainlessSecurityException("Regexes are disabled. Set [script.painless.regex.enabled] to [true] or [limited]");
        }
        int f = 0;
        for (char c : flags.toCharArray()) {
            f |= switch (c) {
                case 'i' -> Pattern.CASE_INSENSITIVE;
                case 'm' -> Pattern.MULTILINE;
                case 's' -> Pattern.DOTALL;
                case 'x' -> Pattern.COMMENTS;
                case 'u' -> Pattern.UNICODE_CASE;
                case 'U' -> Pattern.UNICODE_CHARACTER_CLASS;
                default -> 0;
            };
        }
        return Pattern.compile(pattern, f);
    }
}
