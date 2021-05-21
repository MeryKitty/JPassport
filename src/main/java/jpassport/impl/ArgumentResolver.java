package jpassport.impl;

import jdk.incubator.foreign.*;
import jpassport.NativeLong;
import jpassport.Pointer;
import jpassport.annotations.Length;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureWriter;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class ArgumentResolver {
    public static void resolveArgument(MethodVisitor mw, AnnotatedType annotatedType, int firstLocalSlot, final int scopeLocalVarIndex) {
        // -> ... -> arg
        var type = annotatedType.getType();
        var rawType = InternalUtils.rawType(type);
        if (rawType == Pointer.class) {
            // -> ... -> arg
            var pointedType = ((ParameterizedType) type).getActualTypeArguments()[0];
            var pointedRawType = InternalUtils.rawType(pointedType);
            // -> ... -> pointedArg (uncasted)
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(Pointer.class),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            // -> ... -> pointedArg
            mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(pointedRawType));
            // -> ... -> pointedArg -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            if (InternalUtils.isPrimitive(pointedType)) {
                // -> ... -> pointedArg -> scope
                // -> ... -> pointedArg -> pointedArgSeg
                allocatePrimitive(mw, pointedRawType);
            } else if (pointedRawType.isArray()) {
                // -> ... -> pointedArg -> scope
                // -> ... -> scope -> pointedArg
                mw.visitInsn(Opcodes.SWAP);
                // -> ... -> pointedArg -> scope -> pointedArg
                mw.visitInsn(Opcodes.DUP_X1);
                // -> ... -> pointedArg -> scope -> pointedArg.len
                mw.visitInsn(Opcodes.ARRAYLENGTH);
                // -> ... -> pointedArg -> pointedArgSeg
                allocateArray(mw, pointedRawType);
            } else if (pointedRawType.isRecord()) {
                // -> ... -> pointedArg -> scope
                // -> ... -> pointedArg -> pointedArgSeg
                allocateRecord(mw, pointedRawType);
            } else if (pointedRawType == Pointer.class || MemoryAddress.class.isAssignableFrom(pointedRawType) || pointedRawType == String.class) {
                // -> ... -> pointedArg -> scope
                // -> ... -> pointedArg -> pointedArgSeg
                allocatePointer(mw);
            } else {
                throw new AssertionError("Unexpected type " + type.getTypeName());
            }
            // -> ... -> arg -> argSeg
            // -> ... -> argSegAddr
            initializeAllocatedObject(mw, pointedType, firstLocalSlot, scopeLocalVarIndex);
        } else if (rawType.isRecord() || rawType.isArray()) {
            // -> ... -> arg
            // -> ... -> arg -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            if (rawType.isArray()) {
                // -> ... -> arg -> scope
                long length = annotatedType.getAnnotation(Length.class).value();
                // -> ... -> arg -> scope -> length
                mw.visitLdcInsn(length);
                // -> ... -> arg -> argSeg
                allocateArray(mw, rawType);
            } else {
                // -> ... -> arg -> argSeg
                allocateRecord(mw, rawType);
            }
            // -> ... -> arg -> argSeg
            // -> ... -> argSeg -> arg -> argSeg
            mw.visitInsn(Opcodes.DUP_X1);
            // -> ... -> argSeg -> argSeg -> arg
            mw.visitInsn(Opcodes.SWAP);
            // -> ... -> argSeg
            deconstructVariable(mw, rawType, Optional.of(0L), firstLocalSlot, scopeLocalVarIndex);
        } else if (rawType == String.class) {
            // -> ... -> arg
            // -> ... -> arg -> charset
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(StandardCharsets.class),
                    "US_ASCII",
                    Type.getDescriptor(Charset.class));
            // -> ... -> arg -> charset -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            // -> ... -> argSeg
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(CLinker.class),
                    "toCString",
                    Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(String.class), Type.getType(Charset.class), Type.getType(NativeScope.class)),
                    true);
            // -> ... -> argSegAddr
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(MemorySegment.class),
                    "address",
                    Type.getMethodDescriptor(Type.getType(MemoryAddress.class)),
                    true);
        } else if (!InternalUtils.isPrimitive(type) && !MemorySegment.class.isAssignableFrom(rawType) && !MemoryAddress.class.isAssignableFrom(rawType)) {
            throw new AssertionError("Unexpected type " + type.getTypeName());
        }
    }

    /**
     * Deconstruct a local variable into appropriate MemorySegments, the operation is done recursively
     *
     * <pre>
     * Operand stack
     * Before:
     * -> ... -> memSeg -> arg if offset is known at compile time
     * -> ... -> memSeg -> offset -> arg if offset must be resolved at runtime
     * After:
     * -> ...
     * </pre>
     *
     * @param mw
     * @param type
     * @param currentOffset
     * @param currentLocalVarIndex
     * @param scopeLocalVarIndex
     */
    private static void deconstructVariable(MethodVisitor mw, java.lang.reflect.Type type, Optional<Long> currentOffset, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> (loffset) -> arg
        var rawType = InternalUtils.rawType(type);
        if (rawType.isRecord()) {
            // -> ... -> memSeg -> (loffset) -> arg
            // -> ...
            deconstructRecord(mw, type, currentOffset, currentLocalVarIndex, scopeLocalVarIndex);
        } else {
            currentOffset.ifPresent(l -> {
                // -> ... -> memSeg -> arg
                // -> ... -> memSeg -> arg -> loffset
                mw.visitLdcInsn(l);
                if (rawType == long.class || rawType == double.class) {
                    // -> ... -> memSeg -> arg -> loffset
                    // -> ... -> memSeg -> loffset -> arg -> loffset
                    mw.visitInsn(Opcodes.DUP2_X2);
                } else {
                    // -> ... -> memSeg -> arg -> loffset
                    // -> ... -> memSeg -> loffset -> arg -> loffset
                    mw.visitInsn(Opcodes.DUP2_X1);
                }
                // -> ... -> memSeg -> loffset -> arg -> loffset
                // -> ... -> memSeg -> loffset -> arg
                mw.visitInsn(Opcodes.POP2);
            });
            // -> ... -> memSeg -> loffset -> arg
            // MemoryAccess.setByteAtOffset(memSeg, loffset, arg);
            if (rawType == byte.class || rawType == boolean.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setByteAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(byte.class)),
                        false);
            }
            // MemoryAccess.setShortAtOffset(memSeg, loffset, arg);
            else if (rawType == short.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setShortAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(short.class)),
                        false);
            }
            // MemoryAccess.setCharAtOffset(memSeg, loffset, arg);
            else if (rawType == char.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setCharAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(char.class)),
                        false);
            }
            // MemoryAccess.setIntAtOffset(memSeg, loffset, arg);
            else if (rawType == int.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setIntAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(int.class)),
                        false);
            } else if (rawType == NativeLong.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ... -> memSeg -> loffset -> largValue
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(NativeLong.class),
                        "value",
                        Type.getMethodDescriptor(Type.getType(long.class)),
                        false);
                if (CLinker.C_LONG.byteSize() == 4) {
                    // -> ... -> memSeg -> loffset -> largValue
                    // -> ... -> memSeg -> loffset -> argValue
                    mw.visitInsn(Opcodes.L2I);
                    // -> ...
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "setIntAtOffset",
                            Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(int.class)),
                            false);
                } else {
                    // -> ... -> memSeg -> loffset -> largValue
                    // -> ...
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "setLongAtOffset",
                            Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(long.class)),
                            false);
                }
            }
            // MemoryAccess.setLongAtOffset(memSeg, loffset, arg);
            else if (rawType == long.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setLongAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(long.class)),
                        false);
            }
            // MemoryAccess.setFloatAtOffset(memSeg, loffset, arg);
            else if (rawType == float.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setFloatAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(float.class)),
                        false);
            }
            // MemoryAccess.setDoubleAtOffset(memSeg, loffset, arg);
            else if (rawType == double.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setDoubleAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(double.class)),
                        false);
            } else if (rawType.isArray()) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                deconstructArray(mw, type, currentLocalVarIndex, scopeLocalVarIndex);
            } else if (rawType == Pointer.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                deconstructPointer(mw, type, currentLocalVarIndex, scopeLocalVarIndex);
            }
            // var argSeg = CLinker.toCString(arg, StandardCharsets.US_ASCII, scope);
            // var argSegAddr = argSeg.address();
            // MemoryAccess.setAddressAtOffset(memSeg, loffset, argSegAddr);
            else if (rawType == String.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ... -> memSeg -> loffset -> arg -> charset
                mw.visitFieldInsn(Opcodes.GETSTATIC,
                        Type.getInternalName(StandardCharsets.class),
                        "US_ASCII",
                        Type.getDescriptor(Charset.class));
                // -> ... -> memSeg -> loffset -> arg -> charset -> scope
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
                // -> ... -> memSeg -> loffset -> argSeg
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(CLinker.class),
                        "toCString",
                        Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(String.class), Type.getType(Charset.class), Type.getType(NativeScope.class)),
                        true);
                // -> ... -> memSeg -> loffset -> argSegAddr
                mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        Type.getInternalName(MemorySegment.class),
                        "address",
                        Type.getMethodDescriptor(Type.getType(MemoryAddress.class)),
                        true);
                // -> ...
                deconstructVariable(mw, MemoryAddress.class, Optional.empty(), currentLocalVarIndex, scopeLocalVarIndex);
            }
            // MemoryAccess.setAddressAtOffset(memSeg, loffset, arg);
            else if (MemoryAddress.class.isAssignableFrom(rawType)) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setAddressAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(Addressable.class)),
                        false);
            } else {
                throw new AssertionError("Unexpected type " + type.getTypeName());
            }
            // -> ...
        }
        // -> ...
    }

    private static void deconstructArray(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> lbaseOffset -> array
        var methodStart = new Label();
        var forStart = new Label();
        var forEnd = new Label();
        java.lang.reflect.Type componentType;
        if (type instanceof GenericArrayType g) {
            componentType = g.getGenericComponentType();
        } else if (type instanceof Class<?> c){
            componentType = c.componentType();
        } else {
            throw new AssertionError("Unexpected type " + type.getTypeName() + ", " + type.getClass().getName());
        }
        var componentRawType = InternalUtils.rawType(componentType);
        // -> ... -> memSeg -> lbaseOffset -> array
        mw.visitLabel(methodStart);
        // -> ... -> memSeg -> lbaseOffset
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex + 1);
        // -> ... -> lbaseOffset -> memSeg -> lbaseOffset
        mw.visitInsn(Opcodes.DUP2_X1);
        // -> ... -> lbaseOffset -> memSeg
        mw.visitInsn(Opcodes.POP2);
        // -> ... -> lbaseOffset
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex);
        // -> ... -> lbaseOffset -> array
        mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 1);
        // -> ... -> lbaseOffset -> array.len
        mw.visitInsn(Opcodes.ARRAYLENGTH);
        // -> ... -> lbaseOffset -> array.len -> 0
        mw.visitInsn(Opcodes.ICONST_0);
        {
            // -> ... -> loffset -> array.len -> index
            mw.visitLabel(forStart);
            // -> ... -> array.len -> index -> loffset -> array.len -> index
            mw.visitInsn(Opcodes.DUP2_X2);
            // -> ... -> array.len -> index -> loffset -> array.len - index
            mw.visitInsn(Opcodes.ISUB);

            // Break point
            // -> ... -> array.len -> index -> loffset -> array.len - index
            // -> ... -> array.len -> index -> loffset
            mw.visitJumpInsn(Opcodes.IFLE, forEnd);

            // -> ... -> array.len -> index -> loffset
            // -> ... -> loffset -> array.len -> index -> loffset
            mw.visitInsn(Opcodes.DUP2_X2);
            // -> ... -> loffset -> array.len -> loffset -> index -> loffset
            mw.visitInsn(Opcodes.DUP2_X1);
            // -> ... -> loffset -> array.len -> loffset -> index
            mw.visitInsn(Opcodes.POP2);
            // -> ... -> loffset -> array.len -> loffset -> index -> memSeg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
            // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> index -> memSeg
            mw.visitInsn(Opcodes.DUP2_X2);
            // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> index
            mw.visitInsn(Opcodes.POP);
            // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> index -> array
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 1);
            // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array -> index
            mw.visitInsn(Opcodes.SWAP);
            // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
            ClassGenerator.arrayLoad(mw, componentType);
            // -> ... -> loffset -> array.len -> index
            deconstructVariable(mw, componentType, Optional.empty(), currentLocalVarIndex + 2, scopeLocalVarIndex);

            // -> ... -> loffset -> array.len -> index
            // -> ... -> loffset -> array.len -> index -> 1
            mw.visitInsn(Opcodes.ICONST_1);
            // -> ... -> loffset -> array.len -> index + 1
            mw.visitInsn(Opcodes.IADD);
            // -> ... -> array.len -> index + 1 -> loffset -> array.len -> index + 1
            mw.visitInsn(Opcodes.DUP2_X2);
            // -> ... -> array.len -> index + 1 -> loffset
            mw.visitInsn(Opcodes.POP2);
            long elementSize;
            if (InternalUtils.isPrimitive(componentType)) {
                elementSize = InternalUtils.primitiveLayoutSize(componentType).size();
            } else if (componentRawType.isArray()) {
                throw new AssertionError("Can't reach here");
            } else if (componentRawType.isRecord()) {
                var elementSizeAlign = InternalUtils.recordLayoutSize(componentType);
                elementSize = InternalUtils.align(elementSizeAlign.size(), elementSizeAlign.alignment());
            } else if (componentRawType == Pointer.class) {
                elementSize = CLinker.C_POINTER.byteSize();
            } else if (MemoryAddress.class.isAssignableFrom(componentRawType)) {
                elementSize = CLinker.C_POINTER.byteSize();
            } else {
                throw new AssertionError("Unexpected type " + componentType);
            }
            // -> ... -> array.len -> index + 1 -> loffset
            // -> ... -> array.len -> index + 1 -> loffset -> lcomponentSize
            mw.visitLdcInsn(elementSize);
            // -> ... -> array.len -> index + 1 -> loffset + lcomponentSize
            mw.visitInsn(Opcodes.LADD);
            // -> ... -> loffset + lcomponentSize -> array.len -> index + 1 -> loffset + lcomponentSize
            mw.visitInsn(Opcodes.DUP2_X2);
            // -> ... -> loffset + lcomponentSize -> array.len -> index + 1
            mw.visitInsn(Opcodes.POP2);
            // -> ... -> loffset + lcomponentSize -> array.len -> index + 1
            mw.visitJumpInsn(Opcodes.GOTO, forStart);
        }
        // -> ... -> array.len -> index -> loffset
        mw.visitLabel(forEnd);
        // -> ... -> array.len -> index
        mw.visitInsn(Opcodes.POP2);
        // -> ...
        mw.visitInsn(Opcodes.POP2);

        mw.visitLocalVariable("segment",
                Type.getDescriptor(MemorySegment.class),
                null,
                methodStart, forEnd,
                currentLocalVarIndex);
        var argSig = new SignatureWriter();
        ClassGenerator.signature(argSig, type);
        mw.visitLocalVariable("array",
                Type.getDescriptor(InternalUtils.rawType(type)),
                argSig.toString(),
                methodStart, forEnd,
                currentLocalVarIndex + 1);

        // -> ...
    }

    private static void deconstructRecord(MethodVisitor mw, java.lang.reflect.Type type, Optional<Long> currentOffset, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> record       with nonconstant offset
        // -> ... -> memSeg -> record                  with constant offset
        var methodStart = new Label();
        var methodEnd = new Label();
        // -> ... -> memSeg -> loffset -> record       with nonconstant offset
        // -> ... -> memSeg -> record                  with constant offset
        mw.visitLabel(methodStart);
        // -> ... -> memSeg -> loffset              with nonconstant offset
        // -> ... -> memSeg                         with constant offset
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex);
        // -> ... -> memSeg
        if (currentOffset.isEmpty()) {
            mw.visitVarInsn(Opcodes.LSTORE, currentLocalVarIndex + 1);
        }
        // -> ... -> memSeg
        long relOffset = 0;
        var rawType = InternalUtils.rawType(type);
        for (var component : rawType.getRecordComponents()) {
            // -> ... -> memSeg
            var componentSizeAlign = InternalUtils.layoutSize(component.getAnnotatedType());
            long size = componentSizeAlign.size();
            long align = componentSizeAlign.alignment();
            relOffset = InternalUtils.align(relOffset, align);
            // -> ... -> memSeg -> memSeg
            mw.visitInsn(Opcodes.DUP);
            if (currentOffset.isEmpty()) {
                // -> ... -> memSeg -> memSeg -> lbaseOffset
                mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex + 1);
                // -> ... -> memSeg -> memSeg -> lbaseOffset -> lrelOffset
                mw.visitLdcInsn(relOffset);
                // -> ... -> memSeg -> memSeg -> labsOffset
                mw.visitInsn(Opcodes.LADD);
            }
            // -> ... -> memSeg -> memSeg -> (labsOffset)
            // -> ... -> memSeg -> memSeg -> (labsOffset) -> record
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
            // -> ... -> memSeg -> memSeg -> (labsOffset) -> recordComponent
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(rawType),
                    component.getName(),
                    Type.getMethodDescriptor(component.getAccessor()),
                    false);
            long tempOffset = relOffset;
            // -> ... -> memSeg
            deconstructVariable(mw, component.getGenericType(), currentOffset.map(l -> l + tempOffset), currentOffset.isEmpty() ? currentLocalVarIndex + 3 : currentLocalVarIndex + 1, scopeLocalVarIndex);
            relOffset += size;
        }
        // -> ... -> memSeg
        // -> ...
        mw.visitInsn(Opcodes.POP);
        // -> ...
        mw.visitLabel(methodEnd);

        mw.visitLocalVariable("record",
                Type.getDescriptor(rawType),
                null,
                methodStart, methodEnd,
                currentLocalVarIndex);
        if (currentOffset.isEmpty()) {
            mw.visitLocalVariable("baseOffset",
                    Type.getDescriptor(long.class),
                    null,
                    methodStart, methodEnd,
                    currentLocalVarIndex + 1);
        }
        // -> ...
    }

    private static void deconstructPointer(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> arg
        // -> ...
        var pointedType = ((ParameterizedType) type).getActualTypeArguments()[0];
        var pointedRawType = InternalUtils.rawType(pointedType);
        // -> ... -> memSeg -> loffset -> pointedArg (uncasted)
        mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(Pointer.class),
                "get",
                Type.getMethodDescriptor(Type.getType(Object.class)),
                false);
        // -> ... -> memSeg -> loffset -> pointedArg
        mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(pointedRawType));
        // -> ... -> memSeg -> loffset -> pointedArg -> scope
        mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
        if (InternalUtils.isPrimitive(pointedType)) {
            // -> ... -> memSeg -> loffset -> pointedArg -> scope
            // -> ... -> memSeg -> loffset -> pointedArg -> pointedArgSeg
            allocatePrimitive(mw, pointedType);
        } else if (pointedRawType.isArray()) {
            // -> ... -> memSeg -> loffset -> pointedArg -> scope
            // -> ... -> memSeg -> loffset -> pointedArg -> scope -> pointedArg -> scope
            mw.visitInsn(Opcodes.DUP2);
            // -> ... -> memSeg -> loffset -> pointedArg -> scope -> pointedArg
            mw.visitInsn(Opcodes.POP);
            // -> ... -> memSeg -> loffset -> pointedArg -> scope -> pointedArg.len
            mw.visitInsn(Opcodes.ARRAYLENGTH);
            // -> ... -> memSeg -> loffset -> pointedArg -> pointedArgSeg
            allocateArray(mw, pointedType);
        } else if (pointedRawType.isRecord()) {
            // -> ... -> memSeg -> loffset -> pointedArg -> scope
            // -> ... -> memSeg -> loffset -> pointedArg -> pointedArgSeg
            allocateRecord(mw, pointedType);
        } else if (pointedRawType == Pointer.class) {
            // -> ... -> memSeg -> loffset -> pointedArg -> scope
            // -> ... -> memSeg -> loffset -> pointedArg -> pointedArgSeg
            allocatePointer(mw);
        } else if (MemoryAddress.class.isAssignableFrom(pointedRawType)) {
            // -> ... -> memSeg -> loffset -> pointedArg -> scope
            // -> ... -> memSeg -> loffset -> pointedArg -> pointedArgSeg
            allocatePointer(mw);
        } else {
            throw new AssertionError("Unexpected type " + type.getTypeName());
        }
        // -> ... -> memSeg -> loffset -> pointedArg -> pointedArgSeg
        // -> ... -> memSeg -> loffset -> pointedArgSegAddr
        initializeAllocatedObject(mw, pointedType, currentLocalVarIndex, scopeLocalVarIndex);
        // -> ... -> memSeg -> loffset -> pointedArgSegAddr
        // -> ...
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(MemoryAccess.class),
                "setAddressAtOffset",
                Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(Addressable.class)),
                false);
    }

    private static void allocateArray(MethodVisitor mw, java.lang.reflect.Type type) {
        // -> ... -> scope -> array.len
        java.lang.reflect.Type componentType;
        if (type instanceof GenericArrayType g) {
            componentType = g.getGenericComponentType();
        } else if (type instanceof Class<?> c) {
            componentType = c.componentType();
        } else {
            throw new AssertionError("Unexpected type " + type.getTypeName() + ", " + type.getClass().getName());
        }
        var componentRawType = InternalUtils.rawType(componentType);
        // -> ... -> scope -> larray.len
        mw.visitInsn(Opcodes.I2L);
        long elementSize;
        if (InternalUtils.isPrimitive(componentType)) {
            elementSize = InternalUtils.primitiveLayoutSize(componentType).size();
        } else if (componentRawType.isArray()) {
            throw new AssertionError("Can't reach here");
        } else if (componentRawType.isRecord()) {
            var elementSizeAlign = InternalUtils.recordLayoutSize(componentType);
            elementSize = InternalUtils.align(elementSizeAlign.size(), elementSizeAlign.alignment());
        } else if (componentRawType == Pointer.class){
            elementSize = CLinker.C_POINTER.byteSize();
        } else {
            throw new AssertionError("Unexpected type " + componentType.getTypeName());
        }
        // -> ... -> scope -> larray.len -> lelementSize
        mw.visitLdcInsn(elementSize);
        // -> ... -> scope -> larray.bytesize
        mw.visitInsn(Opcodes.LMUL);
        // -> ... -> seg
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(NativeScope.class),
                "allocate",
                Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(long.class)),
                true);
    }

    private static void allocateRecord(MethodVisitor mw, java.lang.reflect.Type type) {
        // -> ... -> scope
        var rawType = InternalUtils.rawType(type);
        long size = InternalUtils.recordLayoutSize(type).size();
        // -> ... -> scope -> size
        mw.visitLdcInsn(size);
        // -> ... -> seg
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(NativeScope.class),
                "allocate",
                Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(long.class)),
                true);
    }

    private static void allocatePrimitive(MethodVisitor mw, java.lang.reflect.Type type) {
        // -> ... -> scope
        var rawType = InternalUtils.rawType(type);
        long size = InternalUtils.primitiveLayoutSize(type).size();
        // -> ... -> scope -> size
        mw.visitLdcInsn(size);
        // -> ... -> seg
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(NativeScope.class),
                "allocate",
                Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(long.class)),
                true);
    }

    private static void allocatePointer(MethodVisitor mw) {
        // -> ... -> scope
        long size = CLinker.C_POINTER.byteSize();
        // -> ... -> scope -> size
        mw.visitLdcInsn(size);
        // -> ... -> seg
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(NativeScope.class),
                "allocate",
                Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(long.class)),
                true);
    }

    /**
     * This method initialize a newly allocated segment
     *
     * <p>
     * Operand stack:
     * Before: -> ... -> arg -> argSeg
     * After:  -> ... -> argSegAddr
     *
     * @param mw
     * @param type
     * @param currentLocalVarIndex
     * @param scopeLocalVarIndex
     */
    private static void initializeAllocatedObject(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> arg -> argSeg
        if (type != double.class && type != long.class) {
            // -> ... -> argSeg -> arg -> argSeg
            mw.visitInsn(Opcodes.DUP_X1);
            // -> ... -> argSeg -> argSeg -> arg
            mw.visitInsn(Opcodes.SWAP);
        } else {
            // -> ... -> argSeg -> arg -> argSeg
            mw.visitInsn(Opcodes.DUP_X2);
            // -> ... -> argSeg -> argSeg -> arg -> argSeg
            mw.visitInsn(Opcodes.DUP_X2);
            // -> ... -> argSeg -> argSeg -> arg
            mw.visitInsn(Opcodes.POP);
        }
        // -> ... -> argSeg -> argSeg -> arg
        // -> ... -> argSeg
        deconstructVariable(mw, type, Optional.of(0L), currentLocalVarIndex, scopeLocalVarIndex);
        // -> ... -> argSegAddr
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(MemorySegment.class),
                "address",
                Type.getMethodDescriptor(Type.getType(MemoryAddress.class)),
                true);
    }
}
