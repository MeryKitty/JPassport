package jpassport.impl;

import jdk.incubator.foreign.*;
import jpassport.annotations.ArrayValueArg;
import jpassport.annotations.Inline;
import jpassport.annotations.Ptr;
import jpassport.annotations.RefArg;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Parameter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class ArgumentResolver {
    public static void resolveArgument(MethodVisitor mw, Parameter arg, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> arg
        var argType = arg.getType();
        if (arg.isAnnotationPresent(RefArg.class)) {
            // -> ... -> arg
            // -> ... -> arg -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            if (argType.isArray()) {
                // -> ... -> arg -> scope
                // -> ... -> scope -> arg
                mw.visitInsn(Opcodes.SWAP);
                // -> ... -> arg -> scope -> arg
                mw.visitInsn(Opcodes.DUP_X1);
                // -> ... -> arg -> scope -> arg.len
                mw.visitInsn(Opcodes.ARRAYLENGTH);
                // -> ... -> arg -> argSeg
                allocateArray(mw, argType);
            } else {
                // -> ... -> arg -> scope
                // -> ... -> arg -> argSeg
                allocateRecordOrPrimitive(mw, argType);
            }
            // -> ... -> arg -> argSeg
            // -> ... -> argSegAddr
            initializeAllocatedObject(mw, argType, currentLocalVarIndex, scopeLocalVarIndex);
        } else {
            if (argType.isRecord() || argType.isArray()) {
                // -> ... -> arg
                // -> ... -> arg -> scope
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
                if (argType.isArray()) {
                    // -> ... -> arg -> scope
                    long length = Optional.ofNullable(arg.getAnnotation(ArrayValueArg.class)).map(ArrayValueArg::arrayLength).orElseThrow();
                    // -> ... -> arg -> scope -> length
                    mw.visitLdcInsn(length);
                    // -> ... -> arg -> argSeg
                    allocateArray(mw, argType);
                } else {
                    // -> ... -> arg -> argSeg
                    allocateRecordOrPrimitive(mw, argType);
                }
                // -> ... -> arg -> argSeg
                // -> ... -> argSeg -> arg -> argSeg
                mw.visitInsn(Opcodes.DUP_X1);
                // -> ... -> argSeg -> argSeg -> arg
                mw.visitInsn(Opcodes.SWAP);
                // -> ... -> argSeg
                deconstructVariable(mw, argType, Optional.of(0L), currentLocalVarIndex, scopeLocalVarIndex);
            } else if (argType == String.class) {
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
            }
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
     * @param varType
     * @param currentOffset
     * @param currentLocalVarIndex
     * @param scopeLocalVarIndex
     */
    private static void deconstructVariable(MethodVisitor mw, Class<?> varType, Optional<Long> currentOffset, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> arg with nonconstant offset
        // -> ... -> memSeg -> arg with constant offset
        if (varType.isRecord()) {
            // -> ... -> memSeg -> loffset -> arg with nonconstant offset
            // -> ... -> memSeg -> arg -> with constant offset
            deconstructRecord(mw, varType, currentOffset, currentLocalVarIndex, scopeLocalVarIndex);
        } else {
            currentOffset.ifPresent(l -> {
                // -> ... -> memSeg -> arg
                // -> ... -> memSeg -> arg -> loffset
                mw.visitLdcInsn(l);
                if (varType == long.class || varType == double.class) {
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
            if (varType == byte.class || varType == boolean.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setByteAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(byte.class)),
                        false);
            }
            // MemoryAccess.setShortAtOffset(memSeg, loffset, arg);
            else if (varType == short.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setShortAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(short.class)),
                        false);
            }
            // MemoryAccess.setCharAtOffset(memSeg, loffset, arg);
            else if (varType == char.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setCharAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(char.class)),
                        false);
            }
            // MemoryAccess.setIntAtOffset(memSeg, loffset, arg);
            else if (varType == int.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setIntAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(int.class)),
                        false);
            }
            // MemoryAccess.setLongAtOffset(memSeg, loffset, arg);
            else if (varType == long.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setLongAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(long.class)),
                        false);
            }
            // MemoryAccess.setFloatAtOffset(memSeg, loffset, arg);
            else if (varType == float.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setFloatAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(float.class)),
                        false);
            }
            // MemoryAccess.setDoubleAtOffset(memSeg, loffset, arg);
            else if (varType == double.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setDoubleAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(double.class)),
                        false);
            }
            // MemoryAccess.setAddressAtOffset(memSeg, loffset, arg);
            else if (varType == MemoryAddress.class) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setAddressAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(Addressable.class)),
                        false);
            } else if (varType.isArray()) {
                // -> ... -> memSeg -> loffset -> arg
                // -> ...
                deconstructArray(mw, varType, currentLocalVarIndex, scopeLocalVarIndex);
            }
            // var argSeg = CLinker.toCString(arg, StandardCharsets.US_ASCII, scope);
            // var argSegAddr = argSeg.address();
            // MemoryAccess.setAddressAtOffset(memSeg, loffset, argSegAddr);
            else if (varType == String.class) {
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
            } else {
                throw new AssertionError();
            }
            // -> ...
        }
        // -> ...
    }

    private static void deconstructArray(MethodVisitor mw, Class<?> varType, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> lbaseOffset -> array
        var methodStart = new Label();
        var forStart = new Label();
        var forEnd = new Label();
        var componentType = varType.componentType();
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
            if (componentType.isArray()) {
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array -> index
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                mw.visitInsn(Opcodes.AALOAD);
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index] -> array[index]
                mw.visitInsn(Opcodes.DUP);
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index] -> array[index].len
                mw.visitInsn(Opcodes.ARRAYLENGTH);
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index] -> array[index].len -> scope
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index] -> scope -> array[index].len
                mw.visitInsn(Opcodes.SWAP);
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index] -> array[index]Seg
                allocateArray(mw, componentType);
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]SegAddr
                initializeAllocatedObject(mw, componentType, currentLocalVarIndex + 2, scopeLocalVarIndex);
                // -> ... -> loffset -> array.len -> index
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setAddressAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(Addressable.class)),
                        false);
            } else {
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array -> index
                if (componentType == boolean.class || componentType == byte.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.BALOAD);
                } else if (componentType == short.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.SALOAD);
                } else if (componentType == char.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.CALOAD);
                } else if (componentType == int.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.IALOAD);
                } else if (componentType == long.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.LALOAD);
                } else if (componentType == float.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.FALOAD);
                } else if (componentType == double.class) {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.DALOAD);
                } else {
                    // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                    mw.visitInsn(Opcodes.AALOAD);
                }
                // -> ... -> loffset -> array.len -> index -> memSeg -> loffset -> array[index]
                // -> ... -> loffset -> array.len -> index
                deconstructVariable(mw, componentType, Optional.empty(), currentLocalVarIndex + 2, scopeLocalVarIndex);
            }
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
            if (componentType.isArray()) {
                elementSize = CLinker.C_POINTER.byteSize();
            } else if (componentType.isRecord()) {
                var elementRawSizeAlign = InternalUtils.recordLayoutSize(componentType);
                elementSize = InternalUtils.align(elementRawSizeAlign.size(), elementRawSizeAlign.alignment());
            } else {
                elementSize = InternalUtils.primitiveLayoutSize(componentType).size();
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
        mw.visitLocalVariable("array",
                Type.getDescriptor(varType),
                null,
                methodStart, forEnd,
                currentLocalVarIndex + 1);

        // -> ...
    }

    private static void deconstructRecord(MethodVisitor mw, Class<?> varType, Optional<Long> currentOffset, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> record       with nonconstant offset
        // -> ... -> memSeg -> record                  with constant offset
        var methodStart = new Label();
        var methodEnd = new Label();
        // -> ... -> memSeg -> loffset -> record       with nonconstant offset
        // -> ... -> memSeg -> record                  with constant offset
        mw.visitLabel(methodStart);
        // -> ... -> memSeg -> loffset              with nonconstant offset
        // -> ... -> memSeg                         with constant offset
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex + 1);
        // -> ... -> memSeg
        if (currentOffset.isEmpty()) {
            mw.visitVarInsn(Opcodes.LSTORE, currentLocalVarIndex + 2);
        }
        // -> ...
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex);
        long relOffset = 0;
        for (var component : varType.getRecordComponents()) {
            // -> ...
            var componentType = component.getType();
            if (component.isAnnotationPresent(Ptr.class)) {
                // -> ... -> scope
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
                // -> ... -> scope -> record
                mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 1);
                // -> ... -> scope -> recordComponent
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(varType),
                        component.getName(),
                        Type.getMethodDescriptor(component.getAccessor()),
                        false);

                if (componentType.isArray()) {
                    // -> ... -> scope -> recordComponent
                    // -> ... -> recordComponent -> scope -> recordComponent
                    mw.visitInsn(Opcodes.DUP_X1);
                    // -> ... -> recordComponent -> scope -> recordComponent.len
                    mw.visitInsn(Opcodes.ARRAYLENGTH);
                    // -> ... -> recordComponent -> componentSeg
                    allocateArray(mw, componentType);
                } else {
                    // -> ... -> scope -> recordComponent
                    if (componentType != double.class && componentType != long.class) {
                        // -> ... -> recordComponent -> scope
                        mw.visitInsn(Opcodes.SWAP);
                    } else {
                        // -> ... -> recordComponent -> scope -> recordComponent
                        mw.visitInsn(Opcodes.DUP2_X1);
                        // -> ... -> recordComponent -> scope
                        mw.visitInsn(Opcodes.POP2);
                    }
                    // -> ... -> recordComponent -> scope
                    // -> ... -> recordComponent -> componentSeg
                    allocateRecordOrPrimitive(mw, componentType);
                }
                // -> ... -> recordComponent -> componentSeg
                // -> ... -> componentSegAddr
                initializeAllocatedObject(mw, componentType, currentOffset.isEmpty() ? currentLocalVarIndex + 4 : currentLocalVarIndex + 2, scopeLocalVarIndex);
                // -> ... -> componentSegAddr -> memSeg
                mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
                // -> ... -> memSeg -> componentSegAddr
                mw.visitInsn(Opcodes.SWAP);
                if (currentOffset.isEmpty()) {
                    // -> ... -> memSeg -> componentSegAddr -> lbaseOffset
                    mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex + 2);
                    // -> ... -> memSeg -> componentSegAddr -> lbaseOffset -> lrelOffset
                    mw.visitLdcInsn(relOffset);
                    // -> ... -> memSeg -> componentSegAddr -> labsOffset
                    mw.visitInsn(Opcodes.LADD);
                } else {
                    // -> ... -> memSeg -> componentSegAddr -> labsOffset
                    mw.visitLdcInsn(currentOffset.get() + relOffset);
                }
                // -> ... -> memSeg -> componentSegAddr -> labsOffset
                // -> ... -> memSeg -> labsOffset -> componentSegAddr -> labsOffset
                mw.visitInsn(Opcodes.DUP2_X1);
                // -> ... -> memSeg -> labsOffset -> componentSegAddr
                mw.visitInsn(Opcodes.POP2);
                // -> ...
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryAccess.class),
                        "setAddressAtOffset",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(Addressable.class)),
                        false);

                relOffset += CLinker.C_POINTER.byteSize();
            } else {
                // -> ...
                long size;
                long align;
                if (componentType.isArray()) {
                    int length = Optional.ofNullable(component.getAnnotation(Inline.class)).map(Inline::arrayLength).orElseThrow(() -> new RuntimeException("Record array component must be annotated with either " + Ptr.class.getSimpleName() + " or " + Inline.class.getSimpleName()));
                    var componentSizeAndAlign = InternalUtils.arrayLayoutSize(componentType, length);
                    size = componentSizeAndAlign.size();
                    align = componentSizeAndAlign.alignment();
                } else if (componentType.isRecord()) {
                    var componentSizeAndAlign = InternalUtils.recordLayoutSize(componentType);
                    size = componentSizeAndAlign.size();
                    align = componentSizeAndAlign.alignment();
                } else {
                    var componentSizeAndAlign = InternalUtils.primitiveLayoutSize(componentType);
                    size = componentSizeAndAlign.size();
                    align = componentSizeAndAlign.alignment();
                }
                relOffset = InternalUtils.align(relOffset, align);
                // -> ... -> memSeg
                mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
                if (currentOffset.isEmpty()) {
                    // -> ... -> memSeg -> lbaseOffset
                    mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex + 2);
                    // -> ... -> memSeg -> lbaseOffset -> lrelOffset
                    mw.visitLdcInsn(relOffset);
                    // -> ... -> memSeg -> labsOffset
                    mw.visitInsn(Opcodes.LADD);
                }
                // -> ... -> memSeg -> (labsOffset) -> record
                mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 1);
                // -> ... -> memSeg -> (labsOffset) -> recordComponent
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(varType),
                        component.getName(),
                        Type.getMethodDescriptor(component.getAccessor()),
                        false);
                long tempOffset = relOffset;
                // -> ...
                deconstructVariable(mw, componentType, currentOffset.map(l -> l + tempOffset), currentOffset.isEmpty() ? currentLocalVarIndex + 4 : currentLocalVarIndex + 2, scopeLocalVarIndex);
                relOffset += size;
            }
        }
        // -> ...
        mw.visitLabel(methodEnd);

        mw.visitLocalVariable("segment",
                Type.getDescriptor(MemorySegment.class),
                null,
                methodStart, methodEnd,
                currentLocalVarIndex);
        mw.visitLocalVariable("record",
                Type.getDescriptor(varType),
                null,
                methodStart, methodEnd,
                currentLocalVarIndex + 1);
        if (currentOffset.isEmpty()) {
            mw.visitLocalVariable("baseOffset",
                    Type.getDescriptor(long.class),
                    null,
                    methodStart, methodEnd,
                    currentLocalVarIndex + 2);
        }
        // -> ...
    }

    private static void allocateArray(MethodVisitor mw, Class<?> type) {
        // -> ... -> scope -> array.len
        var elementType = type.componentType();
        // -> ... -> scope -> larray.len
        mw.visitInsn(Opcodes.I2L);
        long elementSize;
        if (elementType.isRecord()) {
            var elementSizeAlign = InternalUtils.recordLayoutSize(elementType);
            elementSize = InternalUtils.align(elementSizeAlign.size(), elementSizeAlign.alignment());
        } else if (elementType.isArray()) {
            elementSize = CLinker.C_POINTER.byteSize();
        } else {
            elementSize = InternalUtils.primitiveLayoutSize(elementType).size();
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

    private static void allocateRecordOrPrimitive(MethodVisitor mw, Class<?> type) {
        // -> ... -> scope
        long size;
        if (type.isRecord()) {
            size = InternalUtils.recordLayoutSize(type).size();
        } else {
            size = InternalUtils.primitiveLayoutSize(type).size();
        }
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
    private static void initializeAllocatedObject(MethodVisitor mw, Class<?> type, int currentLocalVarIndex, final int scopeLocalVarIndex) {
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
