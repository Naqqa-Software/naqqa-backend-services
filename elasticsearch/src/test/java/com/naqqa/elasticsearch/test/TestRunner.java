package com.naqqa.elasticsearch.test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class TestRunner {

    private TestRunner() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : "target/test-classes");
        String filter = args.length > 1 ? args[1] : "";
        List<String> classNames = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith("Test.class"))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    classNames.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                });
        }
        int passed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        long start = System.nanoTime();
        for (String className : classNames) {
            if (!filter.isEmpty() && !className.contains(filter)) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue;
            }
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
                continue;
            }
            Method[] methods = type.getDeclaredMethods();
            java.util.Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method method : methods) {
                if (!method.isAnnotationPresent(Test.class)) {
                    continue;
                }
                String name = className + "." + method.getName();
                try {
                    Object instance = type.getDeclaredConstructor().newInstance();
                    method.setAccessible(true);
                    method.invoke(instance);
                    passed++;
                } catch (InvocationTargetException e) {
                    failed++;
                    Throwable cause = e.getCause();
                    failures.add(name + " -> " + cause);
                    System.out.println("FAIL " + name);
                    cause.printStackTrace(System.out);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    failed++;
                    failures.add(name + " -> " + e);
                    System.out.println("FAIL " + name + " " + e);
                }
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println();
        for (String failure : failures) {
            System.out.println("FAILED: " + failure);
        }
        System.out.println("Tests passed: " + passed + ", failed: " + failed + ", time: " + millis + "ms");
        System.exit(failed == 0 ? 0 : 1);
    }
}
