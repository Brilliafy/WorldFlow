package com.cleanroommc.worldflow.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Early-bytecode fixes that must run before Mixin/third-party coremods touch the
 * classes. Keeps AA's generateStructure injection target intact by removing only
 * the ACC_SYNCHRONIZED flag from the original method, never replacing its body.
 */
public final class WorldFlowTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        try {
            if ("net.minecraft.world.gen.structure.MapGenStructure".equals(transformedName)) {
                return stripSynchronized(basicClass, null);
            }
            if ("com.ferreusveritas.dynamictrees.worldgen.WorldGeneratorTrees".equals(transformedName)
                    || "com.lycanitesmobs.core.worldgen.WorldGeneratorDungeon".equals(transformedName)) {
                return stripSynchronized(basicClass, null);
            }
            if ("mirror.normalasm.api.NormalStringPool".equals(transformedName)) {
                return disableStringPool(basicClass);
            }
        } catch (Throwable ignored) {
            // Never brick startup because of an optional optimization.
        }
        return basicClass;
    }

    private static byte[] stripSynchronized(byte[] bytes, String onlyMethod) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (onlyMethod == null || onlyMethod.equals(name)) {
                    access &= ~Opcodes.ACC_SYNCHRONIZED;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static byte[] disableStringPool(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("canonicalize".equals(name) && descriptor != null && descriptor.endsWith(")Ljava/lang/String;")
                        && descriptor.startsWith("(Ljava/lang/String;")) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, TypeLocals.count(descriptor));
                    mv.visitEnd();
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static final class TypeLocals {
        static int count(String descriptor) {
            int args = 0;
            int i = 1;
            while (i < descriptor.length() && descriptor.charAt(i) != ')') {
                char c = descriptor.charAt(i);
                if (c == 'J' || c == 'D') {
                    args += 2;
                    i += 2;
                } else if (c == 'L' || c == '[') {
                    args += 1;
                    while (i < descriptor.length() && descriptor.charAt(i) != ';' && descriptor.charAt(i) != ')') i++;
                    if (i < descriptor.length() && descriptor.charAt(i) == ';') i++;
                } else {
                    args += 1;
                    i++;
                }
            }
            return args;
        }
    }
}
