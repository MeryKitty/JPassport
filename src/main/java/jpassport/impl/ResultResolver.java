package jpassport.impl;

import jpassport.annotations.RefArg;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.util.Optional;

public class ResultResolver {
    public static void resolveResult(MethodVisitor mw, Method method, int currentLocalVarIndex) {
        // -> ... ret
        if (method.isAnnotationPresent(RefArg.class)) {

        }
    }

    private static void constructVariable(MethodVisitor mw, Class<?> varType, Optional<Long> currentOffset, int currentLocalVarIndex) {
        // -> ... -> memSeg -> (loffset)

    }

    private static void constructArray(MethodVisitor mw, Class<?> varType, int currentLocalVarIndex) {
        // -> ... -> memSeg -> loffset
        throw new UnsupportedOperationException();
    }

    private static void constructRecord(MethodVisitor mw, Class<?> varType, Optional<Long> currentOffset, int currentLocalVarIndex) {

    }
}
