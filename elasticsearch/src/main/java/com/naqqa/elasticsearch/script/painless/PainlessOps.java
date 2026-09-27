package com.naqqa.elasticsearch.script.painless;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PainlessOps {

    private PainlessOps() {
    }

    public static boolean isIntegral(Object o) {
        return o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte || o instanceof Character;
    }

    public static boolean isNumber(Object o) {
        return o instanceof Number || o instanceof Character;
    }

    public static double toDouble(Object o) {
        if (o instanceof Character c) {
            return (double) (char) c;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new ClassCastException("Cannot apply arithmetic to non-numeric value [" + o + "]");
    }

    public static long toLong(Object o) {
        if (o instanceof Character c) {
            return (long) (char) c;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        throw new ClassCastException("Cannot apply arithmetic to non-numeric value [" + o + "]");
    }

    public static int toInt(Object o) {
        if (o instanceof Character c) {
            return (int) (char) c;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        throw new ClassCastException("Cannot apply arithmetic to non-numeric value [" + o + "]");
    }

    private static final int T_INT = 0, T_LONG = 1, T_FLOAT = 2, T_DOUBLE = 3;

    private static int kind(Object o) {
        if (o instanceof Double) {
            return T_DOUBLE;
        }
        if (o instanceof Float) {
            return T_FLOAT;
        }
        if (o instanceof Long) {
            return T_LONG;
        }
        return T_INT;
    }

    private static int promote(Object a, Object b) {
        return Math.max(kind(a), kind(b));
    }

    public static Object add(Object a, Object b) {
        if (a instanceof CharSequence || b instanceof CharSequence) {
            return stringOf(a) + stringOf(b);
        }
        requireNumeric(a, "+");
        requireNumeric(b, "+");
        return switch (promote(a, b)) {
            case T_DOUBLE -> (Object) (toDouble(a) + toDouble(b));
            case T_FLOAT -> (Object) ((float) (toDouble(a) + toDouble(b)));
            case T_LONG -> (Object) (toLong(a) + toLong(b));
            default -> (Object) (toInt(a) + toInt(b));
        };
    }

    public static Object sub(Object a, Object b) {
        requireNumeric(a, "-");
        requireNumeric(b, "-");
        return switch (promote(a, b)) {
            case T_DOUBLE -> (Object) (toDouble(a) - toDouble(b));
            case T_FLOAT -> (Object) ((float) (toDouble(a) - toDouble(b)));
            case T_LONG -> (Object) (toLong(a) - toLong(b));
            default -> (Object) (toInt(a) - toInt(b));
        };
    }

    public static Object mul(Object a, Object b) {
        requireNumeric(a, "*");
        requireNumeric(b, "*");
        return switch (promote(a, b)) {
            case T_DOUBLE -> (Object) (toDouble(a) * toDouble(b));
            case T_FLOAT -> (Object) ((float) (toDouble(a) * toDouble(b)));
            case T_LONG -> (Object) (toLong(a) * toLong(b));
            default -> (Object) (toInt(a) * toInt(b));
        };
    }

    public static Object div(Object a, Object b) {
        requireNumeric(a, "/");
        requireNumeric(b, "/");
        int k = promote(a, b);
        if (k == T_LONG && toLong(b) == 0) {
            throw new ArithmeticException("/ by zero");
        }
        if (k == T_INT && toInt(b) == 0) {
            throw new ArithmeticException("/ by zero");
        }
        return switch (k) {
            case T_DOUBLE -> (Object) (toDouble(a) / toDouble(b));
            case T_FLOAT -> (Object) ((float) (toDouble(a) / toDouble(b)));
            case T_LONG -> (Object) (toLong(a) / toLong(b));
            default -> (Object) (toInt(a) / toInt(b));
        };
    }

    public static Object mod(Object a, Object b) {
        requireNumeric(a, "%");
        requireNumeric(b, "%");
        int k = promote(a, b);
        if (k == T_LONG && toLong(b) == 0) {
            throw new ArithmeticException("/ by zero");
        }
        if (k == T_INT && toInt(b) == 0) {
            throw new ArithmeticException("/ by zero");
        }
        return switch (k) {
            case T_DOUBLE -> (Object) (toDouble(a) % toDouble(b));
            case T_FLOAT -> (Object) ((float) (toDouble(a) % toDouble(b)));
            case T_LONG -> (Object) (toLong(a) % toLong(b));
            default -> (Object) (toInt(a) % toInt(b));
        };
    }

    private static void requireNumeric(Object o, String op) {
        if (!isNumber(o)) {
            throw new ClassCastException("Cannot apply [" + op + "] operation to type [" + typeName(o) + "]");
        }
    }

    private static void requireIntegral(Object o, String op) {
        if (!isIntegral(o)) {
            throw new ClassCastException("Cannot apply [" + op + "] operation to type [" + typeName(o) + "]");
        }
    }

    public static Object bitAnd(Object a, Object b) {
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba && bb;
        }
        requireIntegral(a, "&");
        requireIntegral(b, "&");
        return promote(a, b) >= T_LONG ? (Object) (toLong(a) & toLong(b)) : (Object) (toInt(a) & toInt(b));
    }

    public static Object bitOr(Object a, Object b) {
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba || bb;
        }
        requireIntegral(a, "|");
        requireIntegral(b, "|");
        return promote(a, b) >= T_LONG ? (Object) (toLong(a) | toLong(b)) : (Object) (toInt(a) | toInt(b));
    }

    public static Object bitXor(Object a, Object b) {
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba ^ bb;
        }
        requireIntegral(a, "^");
        requireIntegral(b, "^");
        return promote(a, b) >= T_LONG ? (Object) (toLong(a) ^ toLong(b)) : (Object) (toInt(a) ^ toInt(b));
    }

    public static Object shl(Object a, Object b) {
        requireIntegral(a, "<<");
        return a instanceof Long ? (Object) (toLong(a) << toInt(b)) : (Object) (toInt(a) << toInt(b));
    }

    public static Object shr(Object a, Object b) {
        requireIntegral(a, ">>");
        return a instanceof Long ? (Object) (toLong(a) >> toInt(b)) : (Object) (toInt(a) >> toInt(b));
    }

    public static Object ushr(Object a, Object b) {
        requireIntegral(a, ">>>");
        return a instanceof Long ? (Object) (toLong(a) >>> toInt(b)) : (Object) (toInt(a) >>> toInt(b));
    }

    public static Object neg(Object a) {
        requireNumeric(a, "unary -");
        return switch (kind(a)) {
            case T_DOUBLE -> (Object) (-toDouble(a));
            case T_FLOAT -> (Object) (-(float) toDouble(a));
            case T_LONG -> (Object) (-toLong(a));
            default -> (Object) (-toInt(a));
        };
    }

    public static Object bitNot(Object a) {
        requireIntegral(a, "~");
        return a instanceof Long ? (Object) (~toLong(a)) : (Object) (~toInt(a));
    }

    public static boolean truthy(Object a) {
        if (a instanceof Boolean b) {
            return b;
        }
        throw new ClassCastException("Cannot cast [" + typeName(a) + "] to [boolean]");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return Double.compare(toDouble(a), toDouble(b));
        }
        if (a instanceof Comparable ca) {
            return ca.compareTo(b);
        }
        throw new ClassCastException("Cannot compare [" + typeName(a) + "] and [" + typeName(b) + "]");
    }

    public static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (isNumber(a) && isNumber(b)) {
            return toDouble(a) == toDouble(b);
        }
        return Objects.equals(a, b);
    }

    public static Object index(Object receiver, Object key) {
        if (receiver == null) {
            throw new NullPointerException("cannot index a null value");
        }
        if (receiver instanceof com.naqqa.elasticsearch.script.DocLookup lookup) {
            return lookup.get(String.valueOf(key));
        }
        if (receiver instanceof Map<?, ?> m) {
            return m.get(key);
        }
        if (receiver instanceof List<?> l) {
            return l.get(toInt(key));
        }
        if (receiver.getClass().isArray()) {
            return Array.get(receiver, toInt(key));
        }
        if (receiver instanceof CharSequence cs) {
            return cs.charAt(toInt(key));
        }
        throw new ClassCastException("[" + typeName(receiver) + "] cannot be indexed");
    }

    @SuppressWarnings("unchecked")
    public static void indexSet(Object receiver, Object key, Object value) {
        if (receiver instanceof Map<?, ?> m) {
            ((Map<Object, Object>) m).put(key, value);
        } else if (receiver instanceof List<?> l) {
            ((List<Object>) l).set(toInt(key), value);
        } else if (receiver != null && receiver.getClass().isArray()) {
            Array.set(receiver, toInt(key), value);
        } else {
            throw new ClassCastException("[" + typeName(receiver) + "] cannot be indexed for assignment");
        }
    }

    public static Object getProperty(Object receiver, String name) {
        if (receiver == null) {
            throw new NullPointerException("Cannot access [" + name + "] on a null value");
        }
        if (receiver instanceof com.naqqa.elasticsearch.script.DocLookup lookup) {
            return lookup.get(name);
        }
        if (receiver instanceof Map<?, ?> m) {
            return m.get(name);
        }
        if (receiver.getClass().isArray() && name.equals("length")) {
            return Array.getLength(receiver);
        }
        Sandbox.checkClass(receiver.getClass());
        Field field = findField(receiver.getClass(), name);
        if (field != null) {
            try {
                return field.get(receiver);
            } catch (ReflectiveOperationException e) {
                throw new PainlessSecurityException("cannot access field [" + name + "]: " + e.getMessage());
            }
        }
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method getter = findZeroArgMethod(receiver.getClass(), "get" + cap);
        if (getter == null) {
            getter = findZeroArgMethod(receiver.getClass(), "is" + cap);
        }
        if (getter == null) {
            getter = findZeroArgMethod(receiver.getClass(), name);
        }
        if (getter != null) {
            try {
                return getter.invoke(receiver);
            } catch (ReflectiveOperationException e) {
                throw new PainlessSecurityException("cannot access [" + name + "]: " + e.getMessage());
            }
        }
        throw new PainlessSecurityException("field [" + typeName(receiver) + ", " + name + "] not whitelisted");
    }

    private static Field findField(Class<?> type, String name) {
        for (Field f : type.getFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private static Method findZeroArgMethod(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    public static Object callMethod(Object receiver, String name, Object[] args) {
        if (receiver == null) {
            throw new NullPointerException("Cannot call [" + name + "] on a null value");
        }
        Sandbox.checkClass(receiver.getClass());
        Method method = resolveMethod(receiver.getClass(), name, args, false);
        try {
            return method.invoke(receiver, coerceArgs(method, args));
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new PainlessSecurityException("cannot call [" + name + "]: " + cause);
        }
    }

    public static Object callStatic(Class<?> type, String name, Object[] args) {
        Sandbox.checkClass(type);
        Method method = resolveMethod(type, name, args, true);
        try {
            return method.invoke(null, coerceArgs(method, args));
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new PainlessSecurityException("cannot call [" + name + "]: " + cause);
        }
    }

    private static Method resolveMethod(Class<?> type, String name, Object[] args, boolean staticOnly) {
        Method best = null;
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                continue;
            }
            if (staticOnly && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (canCoerce(m, args)) {
                if (best == null || moreSpecific(m, best)) {
                    best = m;
                }
            }
        }
        if (best == null) {
            throw new PainlessSecurityException("Unknown call [" + name + "] with [" + args.length + "] arguments on instance of [" + type.getName() + "]");
        }
        return best;
    }

    private static boolean moreSpecific(Method a, Method b) {
        return true;
    }

    private static boolean canCoerce(Method m, Object[] args) {
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (!canCoerceValue(params[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean canCoerceValue(Class<?> paramType, Object value) {
        if (value == null) {
            return !paramType.isPrimitive();
        }
        Class<?> boxed = box(paramType);
        if (boxed.isInstance(value)) {
            return true;
        }
        if (isNumberClass(boxed) && isNumber(value)) {
            return true;
        }
        if (paramType == Object.class) {
            return true;
        }
        if (value instanceof PainlessLambda && PainlessLambda.isFunctionalInterface(paramType)) {
            return true;
        }
        return false;
    }

    private static Object[] coerceArgs(Method m, Object[] args) {
        Class<?>[] params = m.getParameterTypes();
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = coerceValue(params[i], args[i]);
        }
        return out;
    }

    private static Object coerceValue(Class<?> paramType, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> boxed = box(paramType);
        if (boxed.isInstance(value)) {
            return value;
        }
        if (isNumberClass(boxed) && isNumber(value)) {
            if (boxed == Integer.class) {
                return toInt(value);
            }
            if (boxed == Long.class) {
                return toLong(value);
            }
            if (boxed == Double.class) {
                return toDouble(value);
            }
            if (boxed == Float.class) {
                return (float) toDouble(value);
            }
            if (boxed == Short.class) {
                return (short) toInt(value);
            }
            if (boxed == Byte.class) {
                return (byte) toInt(value);
            }
        }
        if (value instanceof PainlessLambda lambda && PainlessLambda.isFunctionalInterface(paramType)) {
            return PainlessLambda.asInterface(paramType, lambda);
        }
        return value;
    }

    private static boolean isNumberClass(Class<?> c) {
        return c == Integer.class || c == Long.class || c == Double.class || c == Float.class || c == Short.class || c == Byte.class;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == boolean.class) return Boolean.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    public static String stringOf(Object o) {
        return o == null ? "null" : o.toString();
    }

    public static String typeName(Object o) {
        return o == null ? "def (null)" : o.getClass().getName();
    }

    public static Object castTo(String type, Object value) {
        return switch (type) {
            case "int" -> toInt(value);
            case "long" -> toLong(value);
            case "float" -> (float) toDouble(value);
            case "double" -> toDouble(value);
            case "byte" -> (byte) toInt(value);
            case "short" -> (short) toInt(value);
            case "char" -> value instanceof Character c ? c : (char) toInt(value);
            case "boolean" -> (Boolean) value;
            case "String" -> value == null ? null : value.toString();
            case "def", "Object" -> value;
            default -> {
                Class<?> target = Sandbox.resolveTypeName(type);
                if (value != null && !target.isInstance(value)) {
                    throw new ClassCastException("Cannot cast [" + typeName(value) + "] to [" + type + "]");
                }
                yield value;
            }
        };
    }

    public static boolean isInstance(String type, Object value) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case "int" -> value instanceof Integer;
            case "long" -> value instanceof Long;
            case "float" -> value instanceof Float;
            case "double" -> value instanceof Double;
            case "boolean" -> value instanceof Boolean;
            case "String", "def" -> type.equals("def") || value instanceof String;
            case "List" -> value instanceof List;
            case "Map" -> value instanceof Map;
            default -> Sandbox.resolveTypeName(type).isInstance(value);
        };
    }

    public static Object binaryOp(Object l, Object r, String op) {
        return switch (op) {
            case "+" -> add(l, r);
            case "-" -> sub(l, r);
            case "*" -> mul(l, r);
            case "/" -> div(l, r);
            case "%" -> mod(l, r);
            case "&" -> bitAnd(l, r);
            case "|" -> bitOr(l, r);
            case "^" -> bitXor(l, r);
            case "<<" -> shl(l, r);
            case ">>" -> shr(l, r);
            case ">>>" -> ushr(l, r);
            case "<" -> compare(l, r) < 0;
            case "<=" -> compare(l, r) <= 0;
            case ">" -> compare(l, r) > 0;
            case ">=" -> compare(l, r) >= 0;
            case "==" -> valueEquals(l, r);
            case "!=" -> !valueEquals(l, r);
            case "===" -> l == r;
            case "!==" -> l != r;
            default -> throw new PainlessRuntimeError("unknown binary operator [" + op + "]");
        };
    }

    public static Object unaryOp(Object v, String op) {
        return switch (op) {
            case "-" -> neg(v);
            case "+" -> {
                toDouble(v);
                yield v;
            }
            case "!" -> !truthy(v);
            case "~" -> bitNot(v);
            default -> throw new PainlessRuntimeError("unknown unary operator [" + op + "]");
        };
    }

    public static Boolean isInstanceObj(String type, Object value) {
        return isInstance(type, value);
    }

    public static Object getStaticProperty(Class<?> type, String name) {
        Sandbox.checkClass(type);
        try {
            Field f = type.getField(name);
            return f.get(null);
        } catch (NoSuchFieldException e) {
            Method m = findZeroArgMethod(type, name);
            if (m != null && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                try {
                    return m.invoke(null);
                } catch (ReflectiveOperationException ex) {
                    throw new PainlessSecurityException("cannot access [" + name + "]: " + ex.getMessage());
                }
            }
            throw new PainlessSecurityException("field [" + type.getName() + ", " + name + "] not whitelisted");
        } catch (ReflectiveOperationException e) {
            throw new PainlessSecurityException("cannot access field [" + name + "]: " + e.getMessage());
        }
    }

    public static Object instantiate(Class<?> type, Object[] args) {
        Sandbox.checkClass(type);
        java.lang.reflect.Constructor<?> best = null;
        for (java.lang.reflect.Constructor<?> c : type.getConstructors()) {
            if (c.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] params = c.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!canCoerceValue(params[i], args[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                best = c;
                break;
            }
        }
        if (best == null) {
            throw new PainlessSecurityException("Unknown constructor [" + type.getName() + "] with [" + args.length + "] arguments");
        }
        try {
            Class<?>[] params = best.getParameterTypes();
            Object[] coerced = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                coerced[i] = coerceValue(params[i], args[i]);
            }
            return best.newInstance(coerced);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new PainlessSecurityException("cannot construct [" + type.getName() + "]: " + cause);
        }
    }

    public static List<Object> newList(int size) {
        return new ArrayList<>(size);
    }

    public static String join(List<?> items, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(stringOf(items.get(i)));
        }
        return sb.toString();
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
