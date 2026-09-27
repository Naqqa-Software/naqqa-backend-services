package com.naqqa.elasticsearch.script.painless;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClassFileWriter {

    public static final int ACONST_NULL = 0x01;
    public static final int ICONST_0 = 0x03;
    public static final int ICONST_1 = 0x04;
    public static final int BIPUSH = 0x10;
    public static final int SIPUSH = 0x11;
    public static final int LDC_W = 0x13;
    public static final int LDC2_W = 0x14;
    public static final int ALOAD = 0x19;
    public static final int ASTORE = 0x3a;
    public static final int POP = 0x57;
    public static final int DUP = 0x59;
    public static final int IFEQ = 0x99;
    public static final int IFNE = 0x9a;
    public static final int GOTO = 0xa7;
    public static final int ARETURN = 0xb0;
    public static final int RETURN = 0xb1;
    public static final int INVOKEVIRTUAL = 0xb6;
    public static final int INVOKESPECIAL = 0xb7;
    public static final int INVOKESTATIC = 0xb8;
    public static final int INVOKEINTERFACE = 0xb9;
    public static final int NEW = 0xbb;
    public static final int ANEWARRAY = 0xbd;
    public static final int ATHROW = 0xbf;
    public static final int CHECKCAST = 0xc0;
    public static final int AASTORE = 0x53;
    public static final int DUP_X1 = 0x5a;
    public static final int IFNULL = 0xc6;
    public static final int IFNONNULL = 0xc7;

    public static final class ConstantPool {
        private final List<byte[]> entries = new ArrayList<>();
        private final Map<String, Integer> utf8Cache = new HashMap<>();
        private final Map<String, Integer> classCache = new HashMap<>();
        private final Map<String, Integer> stringCache = new HashMap<>();
        private final Map<Object, Integer> numberCache = new HashMap<>();

        private int add(byte[] entry) {
            entries.add(entry);
            return entries.size();
        }

        private void addWide(byte[] entry) {
            entries.add(entry);
            entries.add(null);
        }

        public int utf8(String value) {
            return utf8Cache.computeIfAbsent(value, v -> {
                byte[] bytes = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] entry = new byte[3 + bytes.length];
                entry[0] = 1;
                entry[1] = (byte) (bytes.length >> 8);
                entry[2] = (byte) bytes.length;
                System.arraycopy(bytes, 0, entry, 3, bytes.length);
                return add(entry);
            });
        }

        public int classRef(String internalName) {
            return classCache.computeIfAbsent(internalName, v -> {
                int nameIdx = utf8(v);
                return add(tag(7, nameIdx));
            });
        }

        public int stringConst(String value) {
            return stringCache.computeIfAbsent(value, v -> {
                int idx = utf8(v);
                return add(tag(8, idx));
            });
        }

        public int nameAndType(String name, String desc) {
            int n = utf8(name);
            int d = utf8(desc);
            return add(tag2(12, n, d));
        }

        public int methodref(String ownerInternal, String name, String desc) {
            int c = classRef(ownerInternal);
            int nt = nameAndType(name, desc);
            return add(tag2(10, c, nt));
        }

        public int interfaceMethodref(String ownerInternal, String name, String desc) {
            int c = classRef(ownerInternal);
            int nt = nameAndType(name, desc);
            return add(tag2(11, c, nt));
        }

        public int fieldref(String ownerInternal, String name, String desc) {
            int c = classRef(ownerInternal);
            int nt = nameAndType(name, desc);
            return add(tag2(9, c, nt));
        }

        public int integerConst(int value) {
            Integer key = value;
            Integer cached = numberCache.get(key);
            if (cached != null) {
                return cached;
            }
            byte[] entry = new byte[5];
            entry[0] = 3;
            writeInt(entry, 1, value);
            int idx = add(entry);
            numberCache.put(key, idx);
            return idx;
        }

        public int floatConst(float value) {
            byte[] entry = new byte[5];
            entry[0] = 4;
            writeInt(entry, 1, Float.floatToIntBits(value));
            return add(entry);
        }

        public int longConst(long value) {
            byte[] entry = new byte[9];
            entry[0] = 5;
            writeInt(entry, 1, (int) (value >>> 32));
            writeInt(entry, 5, (int) value);
            int idx = entries.size() + 1;
            addWide(entry);
            return idx;
        }

        public int doubleConst(double value) {
            long bits = Double.doubleToLongBits(value);
            byte[] entry = new byte[9];
            entry[0] = 6;
            writeInt(entry, 1, (int) (bits >>> 32));
            writeInt(entry, 5, (int) bits);
            int idx = entries.size() + 1;
            addWide(entry);
            return idx;
        }

        private static byte[] tag(int tag, int a) {
            return new byte[]{(byte) tag, (byte) (a >> 8), (byte) a};
        }

        private static byte[] tag2(int tag, int a, int b) {
            return new byte[]{(byte) tag, (byte) (a >> 8), (byte) a, (byte) (b >> 8), (byte) b};
        }

        private static void writeInt(byte[] buf, int offset, int value) {
            buf[offset] = (byte) (value >>> 24);
            buf[offset + 1] = (byte) (value >>> 16);
            buf[offset + 2] = (byte) (value >>> 8);
            buf[offset + 3] = (byte) value;
        }

        void writeTo(ByteArrayOutputStream out) {
            writeU2(out, entries.size() + 1);
            for (byte[] entry : entries) {
                if (entry != null) {
                    out.writeBytes(entry);
                }
            }
        }
    }

    public static final class Label {
        int position = -1;
        final List<Integer> fixups = new ArrayList<>();
    }

    public static final class CodeBuilder {
        private final ByteArrayOutputStream code = new ByteArrayOutputStream();
        private int maxStack;
        private int stackDepth;

        public Label newLabel() {
            return new Label();
        }

        public void mark(Label label) {
            label.position = code.size();
            for (int fixupPos : label.fixups) {
                int offset = label.position - fixupPos + 1;
                byte[] buf = code.toByteArray();
                buf[fixupPos] = (byte) (offset >> 8);
                buf[fixupPos + 1] = (byte) offset;
                code.reset();
                code.writeBytes(buf);
            }
            label.fixups.clear();
        }

        private void trackStack(int delta) {
            stackDepth += delta;
            if (stackDepth > maxStack) {
                maxStack = stackDepth;
            }
        }

        public void op(int opcode) {
            code.write(opcode);
        }

        public void opStack(int opcode, int stackDelta) {
            code.write(opcode);
            trackStack(stackDelta);
        }

        public void aconstNull() {
            opStack(ACONST_NULL, 1);
        }

        public void iconst(int value) {
            opStack(value == 0 ? ICONST_0 : ICONST_1, 1);
        }

        public void aload(int slot) {
            opStack(ALOAD, 1);
            code.write(slot);
        }

        public void astore(int slot) {
            opStack(ASTORE, -1);
            code.write(slot);
        }

        public void pop() {
            opStack(POP, -1);
        }

        public void dup() {
            opStack(DUP, 1);
        }

        public void dupX1() {
            opStack(DUP_X1, 1);
        }

        public void ifnull(Label label) {
            code.write(IFNULL);
            trackStack(-1);
            emitBranchTarget(label);
        }

        public void ifnonnull(Label label) {
            code.write(IFNONNULL);
            trackStack(-1);
            emitBranchTarget(label);
        }

        public void ldc(int poolIndex) {
            opStack(LDC_W, 1);
            u2(poolIndex);
        }

        public void ldc2(int poolIndex) {
            opStack(LDC2_W, 1);
            u2(poolIndex);
        }

        public void newObj(int classIndex) {
            opStack(NEW, 1);
            u2(classIndex);
        }

        public void checkcast(int classIndex) {
            code.write(CHECKCAST);
            u2(classIndex);
        }

        public void anewarray(int classIndex) {
            opStack(ANEWARRAY, 0);
            u2(classIndex);
        }

        public void aastore() {
            opStack(AASTORE, -3);
        }

        public void athrow() {
            opStack(ATHROW, -1);
        }

        public void invoke(int opcode, int methodrefIndex, int argCount, boolean hasReturn, int interfaceCountByte) {
            code.write(opcode);
            u2(methodrefIndex);
            if (opcode == INVOKEINTERFACE) {
                code.write(interfaceCountByte);
                code.write(0);
            }
            int receiverAdjust = (opcode == INVOKESTATIC) ? 0 : 1;
            trackStack(-(argCount + receiverAdjust) + (hasReturn ? 1 : 0));
        }

        public void areturn() {
            opStack(ARETURN, -1);
        }

        public void vreturn() {
            code.write(RETURN);
        }

        public void gotoLabel(Label label) {
            code.write(GOTO);
            emitBranchTarget(label);
        }

        public void ifeq(Label label) {
            code.write(IFEQ);
            trackStack(-1);
            emitBranchTarget(label);
        }

        public void ifne(Label label) {
            code.write(IFNE);
            trackStack(-1);
            emitBranchTarget(label);
        }

        private void emitBranchTarget(Label label) {
            if (label.position >= 0) {
                int offset = label.position - code.size() + 1;
                u2(offset);
            } else {
                int fixupPos = code.size();
                label.fixups.add(fixupPos);
                code.write(0);
                code.write(0);
            }
        }

        private void u2(int value) {
            code.write(value >> 8);
            code.write(value);
        }

        public byte[] bytes() {
            return code.toByteArray();
        }

        public int maxStack() {
            return Math.max(maxStack, 1);
        }
    }

    public record MethodDef(int accessFlags, String name, String descriptor, int maxLocals, CodeBuilder code) {}

    public static byte[] build(String internalClassName, String superInternalName, List<String> interfaceInternalNames, List<MethodDef> methods, ConstantPool pool) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int thisClass = pool.classRef(internalClassName);
        int superClass = pool.classRef(superInternalName);
        int[] interfaces = new int[interfaceInternalNames.size()];
        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = pool.classRef(interfaceInternalNames.get(i));
        }
        int codeAttrNameIndex = pool.utf8("Code");

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeU2(body, 0x21);
        writeU2(body, thisClass);
        writeU2(body, superClass);
        writeU2(body, interfaces.length);
        for (int i : interfaces) {
            writeU2(body, i);
        }
        writeU2(body, 0);
        writeU2(body, methods.size());
        for (MethodDef m : methods) {
            int nameIdx = pool.utf8(m.name());
            int descIdx = pool.utf8(m.descriptor());
            writeU2(body, m.accessFlags());
            writeU2(body, nameIdx);
            writeU2(body, descIdx);
            writeU2(body, 1);
            writeU2(body, codeAttrNameIndex);
            byte[] codeBytes = m.code().bytes();
            ByteArrayOutputStream codeAttr = new ByteArrayOutputStream();
            writeU2(codeAttr, m.code().maxStack());
            writeU2(codeAttr, m.maxLocals());
            writeU4(codeAttr, codeBytes.length);
            codeAttr.writeBytes(codeBytes);
            writeU2(codeAttr, 0);
            writeU2(codeAttr, 0);
            byte[] codeAttrBytes = codeAttr.toByteArray();
            writeU4(body, codeAttrBytes.length);
            body.writeBytes(codeAttrBytes);
        }
        writeU2(body, 0);

        writeU4(out, 0xCAFEBABE);
        writeU2(out, 0);
        writeU2(out, 49);
        pool.writeTo(out);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }

    private static void writeU4(ByteArrayOutputStream out, long v) {
        out.write((int) (v >> 24));
        out.write((int) (v >> 16));
        out.write((int) (v >> 8));
        out.write((int) v);
    }

    private ClassFileWriter() {
    }
}
