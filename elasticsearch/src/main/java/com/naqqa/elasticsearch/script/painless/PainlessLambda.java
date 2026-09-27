package com.naqqa.elasticsearch.script.painless;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

public final class PainlessLambda {

    @FunctionalInterface
    public interface Invoker {
        Object invoke(Object[] args);
    }

    private final int arity;
    private final Invoker invoker;

    public PainlessLambda(int arity, Invoker invoker) {
        this.arity = arity;
        this.invoker = invoker;
    }

    public int arity() {
        return arity;
    }

    public Object call(Object... args) {
        return invoker.invoke(args);
    }

    public static Object asInterface(Class<?> iface, PainlessLambda lambda) {
        return Proxy.newProxyInstance(PainlessLambda.class.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "PainlessLambda@" + Integer.toHexString(System.identityHashCode(lambda));
                    case "hashCode" -> System.identityHashCode(lambda);
                    case "equals" -> args != null && args.length > 0 && proxy == args[0];
                    default -> null;
                };
            }
            return lambda.call(args == null ? new Object[0] : args);
        });
    }

    public static boolean isFunctionalInterface(Class<?> type) {
        if (!type.isInterface()) {
            return false;
        }
        int abstractCount = 0;
        for (Method m : type.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && !m.isDefault() && !isObjectMethod(m)) {
                abstractCount++;
            }
        }
        return abstractCount == 1;
    }

    private static boolean isObjectMethod(Method m) {
        return switch (m.getName()) {
            case "equals" -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == Object.class;
            case "hashCode", "toString" -> m.getParameterCount() == 0;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return "PainlessLambda(" + arity + " args)";
    }
}
