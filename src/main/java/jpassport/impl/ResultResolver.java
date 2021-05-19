package jpassport.impl;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.AnnotatedType;
import java.util.Optional;

public class ResultResolver {
    public static void resolveResult(MethodVisitor mw, AnnotatedType annotatedType, int firstLocalSlot, boolean isArgument) {
        // -> ... modArg -> (arg)
        if (isArgument) {
            // -> ... -> modArg -> arg
            // -> ... -> arg -> modArg
            mw.visitInsn(Opcodes.SWAP);
            // -> ... -> arg
            mw.visitInsn(Opcodes.POP);
        } else {
            // -> ... -> modArg
        }

    }

    private static void constructVariable(MethodVisitor mw, java.lang.reflect.Type type, Optional<Long> currentOffset, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> memSeg -> (loffset)

    }

    private static void constructArray(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> memSeg -> loffset
        throw new UnsupportedOperationException();
    }

    private static void constructRecord(MethodVisitor mw, java.lang.reflect.Type type, Optional<Long> currentOffset, int currentLocalVarIndex, boolean isArgument) {

    }

    private static void constructPointer(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, boolean isArgument) {

    }
}
