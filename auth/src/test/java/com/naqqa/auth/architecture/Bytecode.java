package com.naqqa.auth.architecture;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class Bytecode {

    record Ref(String owner, String name, String desc) {

        static Ref of(Method m) {
            return new Ref(Type.getInternalName(m.getDeclaringClass()), m.getName(), Type.getMethodDescriptor(m));
        }

        String simple() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    static final class Info {
        final Ref ref;
        final int access;
        final Set<Ref> calls = new LinkedHashSet<>();
        final Set<String> strings = new LinkedHashSet<>();
        final Set<String> types = new LinkedHashSet<>();
        final Set<String> fieldsRead = new LinkedHashSet<>();

        Info(Ref ref, int access) {
            this.ref = ref;
            this.access = access;
        }

        boolean concrete() {
            return (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
        }
    }

    static final class Node {
        final String name;
        final String superName;
        final List<String> interfaces;
        final Map<String, Info> methods = new LinkedHashMap<>();

        Node(String name, String superName, String[] interfaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }
    }

    record FieldInit(Set<String> strings, Set<Ref> calls) {
    }

    private final Map<String, Node> types = new LinkedHashMap<>();
    private final Map<String, FieldInit> fieldInits = new LinkedHashMap<>();
    private final Map<String, Set<String>> subtypes = new HashMap<>();

    private Bytecode() {
    }

    static Bytecode of(Class<?> anchor, String packagePath) {
        Bytecode b = new Bytecode();
        try {
            Path root = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (Stream<Path> files = Files.walk(root.resolve(packagePath))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    try (InputStream in = Files.newInputStream(p)) {
                        b.read(in);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        b.types.values().forEach(t -> {
            if (t.superName != null) {
                b.subtypes.computeIfAbsent(t.superName, k -> new LinkedHashSet<>()).add(t.name);
            }
            t.interfaces.forEach(i -> b.subtypes.computeIfAbsent(i, k -> new LinkedHashSet<>()).add(t.name));
        });
        return b;
    }

    private void read(InputStream in) throws IOException {
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            Node type;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                type = new Node(name, superName, interfaces);
                types.put(name, type);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                Info info = new Info(new Ref(type.name, name, descriptor), access);
                type.methods.put(name + descriptor, info);
                return new MethodVisitor(Opcodes.ASM9) {
                    final Set<String> segmentStrings = new LinkedHashSet<>();
                    final Set<Ref> segmentCalls = new LinkedHashSet<>();

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                        info.calls.add(new Ref(owner, mname, mdesc));
                        segmentCalls.add(new Ref(owner, mname, mdesc));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        String field = owner + "." + fname;
                        if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD) {
                            info.fieldsRead.add(field);
                            return;
                        }
                        FieldInit init = fieldInits.computeIfAbsent(field, k -> new FieldInit(new LinkedHashSet<>(), new LinkedHashSet<>()));
                        init.strings().addAll(segmentStrings);
                        init.calls().addAll(segmentCalls);
                        segmentStrings.clear();
                        segmentCalls.clear();
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String typeName) {
                        info.types.add(typeName);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s) {
                            info.strings.add(s);
                            segmentStrings.add(s);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String iname, String idesc, Handle bsm, Object... args) {
                        for (Object a : args) {
                            if (a instanceof Handle h) {
                                info.calls.add(new Ref(h.getOwner(), h.getName(), h.getDesc()));
                            } else if (a instanceof String s) {
                                info.strings.add(s);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    Map<String, FieldInit> fieldInits() {
        return fieldInits;
    }

    Iterable<Info> methods() {
        List<Info> out = new ArrayList<>();
        types.values().forEach(t -> out.addAll(t.methods.values()));
        return out;
    }

    boolean indexed(String owner) {
        return types.containsKey(owner);
    }

    Set<Info> resolve(Ref call) {
        Set<Info> out = new LinkedHashSet<>();
        String key = call.name() + call.desc();
        Deque<String> up = new ArrayDeque<>();
        up.add(call.owner());
        Set<String> seen = new LinkedHashSet<>();
        while (!up.isEmpty()) {
            String n = up.poll();
            Node t = types.get(n);
            if (t == null || !seen.add(n)) {
                continue;
            }
            Info i = t.methods.get(key);
            if (i != null && i.concrete()) {
                out.add(i);
                break;
            }
            if (t.superName != null) {
                up.add(t.superName);
            }
            up.addAll(t.interfaces);
        }
        Deque<String> down = new ArrayDeque<>(subtypes.getOrDefault(call.owner(), Set.of()));
        Set<String> below = new LinkedHashSet<>();
        while (!down.isEmpty()) {
            String n = down.poll();
            if (!below.add(n)) {
                continue;
            }
            Node t = types.get(n);
            if (t != null) {
                Info i = t.methods.get(key);
                if (i != null && i.concrete()) {
                    out.add(i);
                }
            }
            down.addAll(subtypes.getOrDefault(n, Set.of()));
        }
        return out;
    }

    Map<Info, Integer> reachable(Ref start, int depth) {
        Map<Info, Integer> seen = new LinkedHashMap<>();
        Deque<Info> queue = new ArrayDeque<>();
        for (Info i : resolve(start)) {
            seen.put(i, 0);
            queue.add(i);
        }
        while (!queue.isEmpty()) {
            Info cur = queue.poll();
            int d = seen.get(cur);
            if (d >= depth) {
                continue;
            }
            for (Ref c : cur.calls) {
                for (Info next : resolve(c)) {
                    if (!seen.containsKey(next)) {
                        seen.put(next, d + 1);
                        queue.add(next);
                    }
                }
            }
        }
        return seen;
    }

    boolean reaches(Ref start, int depth, Predicate<Ref> target) {
        for (Info i : reachable(start, depth).keySet()) {
            if (target.test(i.ref)) {
                return true;
            }
            for (Ref c : i.calls) {
                if (target.test(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean usesType(Ref start, int depth, String typeName) {
        for (Info i : reachable(start, depth).keySet()) {
            if (i.types.contains(typeName) || i.calls.stream().anyMatch(c -> c.owner().equals(typeName))) {
                return true;
            }
        }
        return false;
    }

    Set<String> stringsNear(Ref start, int depth, Predicate<Ref> sink) {
        Set<String> out = new LinkedHashSet<>();
        for (Info i : reachable(start, depth).keySet()) {
            if (i.calls.stream().anyMatch(sink)) {
                out.addAll(i.strings);
            }
        }
        return out;
    }
}
