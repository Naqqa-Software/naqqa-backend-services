package com.naqqa.auth.architecture;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

final class Endpoints {

    static final String BASE_PLACEHOLDER = "${auth.api.base-path:/api/auth}";
    static final String BASE = "/api/auth";
    static final Set<RequestMethod> SAFE = EnumSet.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE);

    record Endpoint(Class<?> type, Method method, Set<RequestMethod> methods, List<String> paths) {

        String key() {
            return type.getSimpleName() + "#" + method.getName();
        }

        boolean safe() {
            return !methods.isEmpty() && SAFE.containsAll(methods);
        }

        Bytecode.Ref ref() {
            return new Bytecode.Ref(org.springframework.asm.Type.getInternalName(type), method.getName(),
                    org.springframework.asm.Type.getMethodDescriptor(method));
        }
    }

    private Endpoints() {
    }

    static List<Endpoint> of(Class<?> anchor) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return new AnnotationTypeFilter(Controller.class).match(reader, getMetadataReaderFactory());
            }
        };
        TreeMap<String, Endpoint> out = new TreeMap<>();
        String root = anchor.getProtectionDomain().getCodeSource().getLocation().toString();
        for (var candidate : scanner.findCandidateComponents(anchor.getPackageName())) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            if (!type.getProtectionDomain().getCodeSource().getLocation().toString().equals(root)) {
                continue;
            }
            RequestMapping onType = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = onType == null || onType.path().length == 0 ? new String[]{""} : onType.path();
            for (Method m : ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) {
                    continue;
                }
                String[] suffixes = rm.path().length == 0 ? new String[]{""} : rm.path();
                List<String> paths = new ArrayList<>();
                for (String p : prefixes) {
                    for (String s : suffixes) {
                        paths.add((p + s).replace(BASE_PLACEHOLDER, BASE));
                    }
                }
                Set<RequestMethod> methods = rm.method().length == 0 ? EnumSet.noneOf(RequestMethod.class) : EnumSet.copyOf(List.of(rm.method()));
                Endpoint e = new Endpoint(type, m, methods, List.copyOf(paths));
                if (out.put(e.key(), e) != null) {
                    throw new IllegalStateException("overloaded handler name " + e.key());
                }
            }
        }
        return List.copyOf(out.values());
    }
}
