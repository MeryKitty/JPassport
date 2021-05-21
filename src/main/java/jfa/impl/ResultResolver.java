package jfa.impl;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jfa.NativeLong;
import jfa.Pointer;
import jfa.annotations.Length;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureWriter;

import java.lang.reflect.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

public class ResultResolver {
    public static void resolveResult(MethodVisitor mw, AnnotatedType annotatedType, int firstLocalSlot, boolean isArgument) {
        // -> ... -> modNewArg -> (arg)
        var type = annotatedType.getType();
        var rawType = Utils.rawType(type);
        if (rawType == Pointer.class) {
            var start = new Label();
            var end = new Label();
            // -> ... -> modNewArg -> (arg)
            mw.visitLabel(start);
            if (isArgument) {
                // -> ... -> modNewArg -> arg
                // -> ... -> modNewArg
                mw.visitVarInsn(Opcodes.ASTORE, firstLocalSlot);
            }
            // -> ... -> modNewArg
            // -> ... -> newArg
            constructPointerHelper(mw, type, firstLocalSlot, isArgument);
            // -> ... -> newArg
            mw.visitLabel(end);
            if (isArgument){
                var argSig = new SignatureWriter();
                ClassGenerator.signature(argSig, type);
                mw.visitLocalVariable("arg",
                        Type.getDescriptor(Pointer.class),
                        argSig.toString(),
                        start, end,
                        firstLocalSlot);
            }
        } else if (rawType.isArray()) {
            throw new AssertionError("Can't reach here");
        } else if (Utils.isStruct(type)) {
            constructVariable(mw, type, Optional.of(0L), Optional.empty(), firstLocalSlot, isArgument);
        } else if (rawType == String.class) {
            // -> ... -> modNewArg -> (arg)
            if (isArgument) {
                // -> ... -> modNewArg -> arg
                // -> ... -> modNewArg
                mw.visitInsn(Opcodes.POP);
            }
            // -> ... -> modNewArg
            // -> ... -> modNewArg -> charset
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(StandardCharsets.class),
                    "US_ASCII",
                    Type.getDescriptor(Charset.class));
            // -> ... -> newArg
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(CLinker.class),
                    "toJavaStringRestricted",
                    Type.getMethodDescriptor(Type.getType(String.class), Type.getType(MemoryAddress.class), Type.getType(Charset.class)),
                    true);
        } else if (!Utils.isPrimitive(rawType) && !MemorySegment.class.isAssignableFrom(rawType) && !MemoryAddress.class.isAssignableFrom(rawType)) {
            throw new AssertionError("Unexpected type " + type.getTypeName());
        }
        // -> ... -> newArg
    }

    private static void constructVariable(MethodVisitor mw, java.lang.reflect.Type type, Optional<Long> currentOffset, Optional<Integer> arrayLength, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> memSeg -> (loffset) -> (arg)
        var rawType = Utils.rawType(type);
        if (Utils.isStruct(type)) {
            // -> ... -> memSeg -> (loffset) -> (arg)
            constructRecord(mw, type, currentOffset, currentLocalVarIndex, isArgument);
        } else {
            // -> ... -> memSeg -> (loffset) -> (arg)
            if (currentOffset.isPresent()) {
                // -> ... -> memSeg -> (arg)
                // -> ... -> memSeg -> (arg) -> loffset
                mw.visitLdcInsn(currentOffset.get());
                if (isArgument) {
                    // -> ... -> memSeg -> arg -> loffset
                    if (rawType == double.class || rawType == long.class) {
                        // -> ... -> memSeg -> arg -> loffset
                        // -> ... -> memSeg -> loffset -> arg -> loffset
                        mw.visitInsn(Opcodes.DUP2_X2);
                        // -> ... -> memSeg -> loffset -> arg
                        mw.visitInsn(Opcodes.POP2);
                    } else {
                        // -> ... -> memSeg -> arg -> loffset
                        // -> ... -> memSeg -> loffset -> arg -> loffset
                        mw.visitInsn(Opcodes.DUP2_X1);
                        // -> ... -> memSeg -> loffset -> arg
                        mw.visitInsn(Opcodes.POP2);
                    }
                    // -> ... -> memSeg -> loffset -> arg
                }
                // -> ... -> memSeg -> loffset -> (arg)
            }
            // -> ... -> memSeg -> loffset -> (arg)
            if (rawType.isArray()) {
                // -> ... -> memSeg -> loffset -> (arg)
                // -> ... -> newArg
                constructArray(mw, type, arrayLength, currentLocalVarIndex, isArgument);
            } else if (rawType == Pointer.class) {
                // -> ... -> memSeg -> loffset -> (arg)
                // -> ... -> newArg
                constructPointer(mw, type, currentLocalVarIndex, isArgument);
            } else {
                // -> ... -> memSeg -> loffset -> (arg)
                if (isArgument) {
                    // -> ... -> memSeg -> loffset -> arg
                    if (type == double.class || type == long.class) {
                        // -> ... -> memSeg -> loffset
                        mw.visitInsn(Opcodes.POP2);
                    } else {
                        // -> ... -> memSeg -> loffset
                        mw.visitInsn(Opcodes.POP);
                    }
                }
                // -> ... -> memSeg -> loffset
                if (rawType == boolean.class || rawType == byte.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getByteAtOffset",
                            Type.getMethodDescriptor(Type.getType(byte.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == short.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getShortAtOffset",
                            Type.getMethodDescriptor(Type.getType(short.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == char.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getCharAtOffset",
                            Type.getMethodDescriptor(Type.getType(char.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == int.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getIntAtOffset",
                            Type.getMethodDescriptor(Type.getType(int.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == NativeLong.class) {
                    if (CLinker.C_LONG.byteSize() == 4) {
                        // -> ... -> memSeg -> loffset
                        // -> ... -> newArgValue
                        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                                Type.getInternalName(MemoryAccess.class),
                                "getIntAtOffset",
                                Type.getMethodDescriptor(Type.getType(int.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                                false);
                        // -> ... -> lnewArgValue
                        mw.visitInsn(Opcodes.I2L);
                    } else {
                        // -> ... -> memSeg -> loffset
                        // -> ... -> lnewArgValue
                        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                                Type.getInternalName(MemoryAccess.class),
                                "getLongAtOffset",
                                Type.getMethodDescriptor(Type.getType(long.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                                false);
                    }
                    // -> ... -> lnewArgValue -> newArg
                    mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(NativeLong.class));
                    // -> ... -> newArg -> lnewArgValue -> newArg
                    mw.visitInsn(Opcodes.DUP_X2);
                    // -> ... -> newArg -> newArg -> lnewArgValue -> newArg
                    mw.visitInsn(Opcodes.DUP_X2);
                    // -> ... -> newArg -> newArg -> lnewArgValue
                    mw.visitInsn(Opcodes.POP);
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                            Type.getInternalName(NativeLong.class),
                            "<init>",
                            Type.getMethodDescriptor(Type.getType(void.class), Type.getType(long.class)),
                            false);
                } else if (rawType == long.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getLongAtOffset",
                            Type.getMethodDescriptor(Type.getType(long.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == float.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getFloatAtOffset",
                            Type.getMethodDescriptor(Type.getType(float.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == double.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getDoubleAtOffset",
                            Type.getMethodDescriptor(Type.getType(double.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else if (rawType == String.class) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> modNewArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getAddressAtOffset",
                            Type.getMethodDescriptor(Type.getType(MemoryAddress.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                    // -> ... -> modNewArg -> charset
                    mw.visitFieldInsn(Opcodes.GETSTATIC,
                            Type.getInternalName(StandardCharsets.class),
                            "US_ASCII",
                            Type.getDescriptor(Charset.class));
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(CLinker.class),
                            "toJavaStringRestricted",
                            Type.getMethodDescriptor(Type.getType(String.class), Type.getType(MemoryAddress.class), Type.getType(Charset.class)),
                            true);
                } else if (MemoryAddress.class.isAssignableFrom(rawType)) {
                    // -> ... -> memSeg -> loffset
                    // -> ... -> newArg
                    mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                            Type.getInternalName(MemoryAccess.class),
                            "getAddressAtOffset",
                            Type.getMethodDescriptor(Type.getType(MemoryAddress.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                            false);
                } else {
                    throw new AssertionError("Unexpected type " + type.getTypeName());
                }
            }
        }
    }

    private static void constructArray(MethodVisitor mw, java.lang.reflect.Type type, Optional<Integer> arrayLength, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> memSeg -> loffset -> (arg)
        java.lang.reflect.Type componentType;
        if (type instanceof GenericArrayType g) {
            componentType = g.getGenericComponentType();
        } else if (type instanceof Class<?> c) {
            componentType = c.componentType();
        } else {
            throw new AssertionError("Unexpected type " + type.getTypeName() + ", " + type.getClass().getName());
        }
        var componentRawType = Utils.rawType(componentType);
        SizeData componentSizeAndAlign;
        if (Utils.isPrimitive(componentType)) {
            componentSizeAndAlign = Utils.primitiveLayoutSize(componentType);
        } else if (componentRawType.isArray()) {
            throw new AssertionError("Can't reach here");
        } else if (Utils.isStruct(componentType)) {
            componentSizeAndAlign = Utils.recordLayoutSize(componentType);
        } else if (componentRawType == Pointer.class || componentRawType == String.class || MemoryAddress.class.isAssignableFrom(componentRawType)) {
            componentSizeAndAlign = Utils.pointerLayoutSize();
        } else {
            throw new AssertionError("Unexpected component type " + componentType.getTypeName());
        }
        long size = componentSizeAndAlign.size();
        long align = componentSizeAndAlign.alignment();
        long padding = ((size - 1) / align + 1) * align - size;
        var start = new Label();
        var end = new Label();
        var forStart = new Label();
        var forEnd = new Label();
        // -> ... -> memSeg -> loffset -> (arg)
        mw.visitLabel(start);
        if (isArgument) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ... -> memSeg -> loffset
            mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex + 3);
        }
        // -> ... -> memSeg -> loffset
        // -> ... -> memSeg
        mw.visitVarInsn(Opcodes.LSTORE, currentLocalVarIndex);
        if (arrayLength.isPresent()) {
            // -> ... -> memSeg
            // -> ... -> memSeg -> newArg.len
            mw.visitLdcInsn(arrayLength.get());
        } else if (isArgument) {
            // -> ... -> memSeg
            // -> ... -> memSeg -> arg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 3);
            // -> ... -> memSeg -> newArg.len
            mw.visitInsn(Opcodes.ARRAYLENGTH);
        } else {
            throw new IllegalArgumentException("Can't construct array without known length");
        }
        // -> ... -> memSeg -> newArg.len
        // -> ... -> memSeg -> newArg
        ClassGenerator.newArray(mw, componentType);
        // -> ... -> memSeg -> newArg -> 0
        mw.visitInsn(Opcodes.ICONST_0);
        // -> ... -> memSeg -> newArg
        mw.visitVarInsn(Opcodes.ISTORE, currentLocalVarIndex + 2);
        {
            // -> ... -> memSeg -> newArg
            mw.visitLabel(forStart);
            // -> ... -> memSeg -> newArg -> newArg
            mw.visitInsn(Opcodes.DUP);
            // -> ... -> memSeg -> newArg -> newArg.len
            mw.visitInsn(Opcodes.ARRAYLENGTH);
            // -> ... -> memSeg -> newArg -> newArg.len -> i
            mw.visitVarInsn(Opcodes.ILOAD, currentLocalVarIndex + 2);
            // -> ... -> memSeg -> newArg -> newArg.len - i
            mw.visitInsn(Opcodes.ISUB);
            // -> ... -> memSeg -> newArg
            mw.visitJumpInsn(Opcodes.IFLE, forEnd);
            // -> ... -> memSeg -> newArg -> memSeg -> newArg
            mw.visitInsn(Opcodes.DUP2);
            // -> ... -> memSeg -> newArg -> memSeg -> newArg -> i
            mw.visitVarInsn(Opcodes.ILOAD, currentLocalVarIndex + 2);
            // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> newArg -> i
            mw.visitInsn(Opcodes.DUP2_X1);
            // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg
            mw.visitInsn(Opcodes.POP2);
            // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> loffset
            mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex);
            if (isArgument) {
                // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> loffset
                // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> loffset -> arg
                mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 3);
                // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> loffset -> arg -> i
                mw.visitVarInsn(Opcodes.ILOAD, currentLocalVarIndex + 2);
                // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> loffset -> arg[i]
                ClassGenerator.arrayLoad(mw, componentType);
            }
            // -> ... -> memSeg -> newArg -> newArg -> i -> memSeg -> loffset -> (arg)
            // -> ... -> memSeg -> newArg -> newArg -> i -> newArg[i]
            constructVariable(mw, componentType, Optional.empty(), Optional.empty(), isArgument ? currentLocalVarIndex + 4 : currentLocalVarIndex + 3, isArgument);
            // -> ... -> memSeg -> newArg
            ClassGenerator.arrayStore(mw, componentType);
            // -> ... -> memSeg -> newArg -> loffset
            mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex);
            // -> ... -> memSeg -> newArg -> loffset -> lelementSize
            mw.visitLdcInsn(size + padding);
            // -> ... -> memSeg -> newArg -> lnextOffset
            mw.visitInsn(Opcodes.LADD);
            // -> ... -> memSeg -> newArg
            mw.visitVarInsn(Opcodes.LSTORE, currentLocalVarIndex);
            // -> ... -> memSeg -> newArg
            mw.visitIincInsn(currentLocalVarIndex + 2, 1);
            // -> ... -> memSeg -> newArg
            mw.visitJumpInsn(Opcodes.GOTO, forStart);
        }
        // -> ... -> memSeg -> newArg
        mw.visitLabel(forEnd);
        // -> ... -> newArg -> memSeg
        mw.visitInsn(Opcodes.SWAP);
        // -> ... -> newArg
        mw.visitInsn(Opcodes.POP);
        // -> ... -> newArg
        mw.visitLabel(end);
        mw.visitLocalVariable("loffset",
                Type.getDescriptor(long.class),
                null,
                start, end,
                currentLocalVarIndex);
        mw.visitLocalVariable("i",
                Type.getDescriptor(int.class),
                null,
                start, end,
                currentLocalVarIndex + 2);
        var argSig = new SignatureWriter();
        ClassGenerator.signature(argSig, type);
        if (isArgument) {
            mw.visitLocalVariable("arg",
                    Type.getDescriptor(Utils.rawType(type)),
                    argSig.toString(),
                    start, end,
                    currentLocalVarIndex + 3);
        }
    }

    private static void constructRecord(MethodVisitor mw, java.lang.reflect.Type type, Optional<Long> currentOffset, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> memSeg -> (loffset) -> (arg)
        var rawType = Utils.rawType(type);
        int argVarIndex = currentOffset.isPresent() ? currentLocalVarIndex : currentLocalVarIndex + 2;
        int nextVarIndex = isArgument ? argVarIndex + 1 : argVarIndex;
        var start = new Label();
        var end = new Label();
        // -> ... -> memSeg -> (loffset) -> (arg)
        mw.visitLabel(start);
        if (isArgument) {
            // -> ... -> memSeg -> (loffset) -> arg
            // -> ... -> memSeg -> (loffset)
            mw.visitVarInsn(Opcodes.ASTORE, argVarIndex);
        }
        // -> ... -> memSeg -> (loffset)
        if (currentOffset.isEmpty()) {
            // -> ... -> memSeg -> loffset
            // -> ... -> memSeg
            mw.visitVarInsn(Opcodes.LSTORE, currentLocalVarIndex);
        }
        // -> ... -> memSeg
        // -> ... -> memSeg -> newArg
        mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(rawType));
        // -> ... -> newArg -> memSeg -> newArg
        mw.visitInsn(Opcodes.DUP_X1);
        // -> ... -> newArg -> newArg -> memSeg
        mw.visitInsn(Opcodes.SWAP);
        long relOffset = 0;
        for (var component : rawType.getRecordComponents()) {
            // -> ... -> newArg -> newArg -> ... -> memSeg
            var componentSizeAlign = Utils.layoutSize(component.getGenericType(), Optional.ofNullable(component.getAnnotation(Length.class)));
            long size = componentSizeAlign.size();
            long align = componentSizeAlign.alignment();
            relOffset = Utils.align(relOffset, align);
            // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg
            mw.visitInsn(Opcodes.DUP);
            if (currentOffset.isEmpty()) {
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> loffset
                mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex);
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> loffset -> lrelOffset
                mw.visitLdcInsn(relOffset);
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> labsOffset
                mw.visitInsn(Opcodes.LADD);
            }
            // -> ... -> newArg -> newArg -> ... ->memSeg -> memSeg -> (labsOffset)
            if (isArgument) {
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> (labsOffset)
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> (labsOffset) -> arg
                mw.visitVarInsn(Opcodes.ALOAD, argVarIndex);
                // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> (labsOffset) -> component
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(rawType),
                        component.getName(),
                        Type.getMethodDescriptor(Type.getType(component.getType())),
                        false);
            }
            // -> ... -> newArg -> newArg -> ... -> memSeg -> memSeg -> (labsOffset) -> (component)
            long tempOffset = relOffset;
            // -> ... -> newArg -> newArg -> ... -> memSeg -> newComponent
            constructVariable(mw, component.getGenericType(), currentOffset.map(l -> l + tempOffset), Optional.ofNullable(component.getAnnotation(Length.class)).map(Length::value), nextVarIndex, isArgument);
            if (component.getType() == long.class || component.getType() == double.class) {
                // -> ... -> newArg -> newArg -> ... -> memSeg -> newComponent
                // -> ... -> newArg -> newArg -> ... -> newComponent -> memSeg -> newComponent
                mw.visitInsn(Opcodes.DUP2_X1);
                // -> ... -> newArg -> newArg -> ... -> newComponent -> memSeg
                mw.visitInsn(Opcodes.POP2);
            } else {
                // -> ... -> newArg -> newArg -> ... -> memSeg -> newComponent
                // -> ... -> newArg -> newArg -> ... -> newComponent -> memSeg
                mw.visitInsn(Opcodes.SWAP);
            }
            // -> ... -> newArg -> newArg -> ... -> newComponent -> memSeg
            relOffset += size;
        }
        // -> ... -> newArg -> newArg -> componets... -> memSeg
        // -> ... -> newArg -> newArg -> componets...
        mw.visitInsn(Opcodes.POP);
        var constructorDescriptor = Type.getMethodDescriptor(Type.getType(void.class), Arrays.stream(rawType.getRecordComponents())
                .map(RecordComponent::getType)
                .map(Type::getType)
                .toList()
                .toArray(new Type[0]));
        // -> ... -> newArg
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                Type.getInternalName(rawType),
                "<init>",
                constructorDescriptor,
                false);
        mw.visitLabel(end);
        if (currentOffset.isEmpty()) {
            mw.visitLocalVariable("loffset",
                    Type.getDescriptor(long.class),
                    null,
                    start, end,
                    currentLocalVarIndex);
        }
        if (isArgument) {
            mw.visitLocalVariable("arg",
                    Type.getDescriptor(rawType),
                    null,
                    start, end,
                    argVarIndex);
        }
    }

    private static void constructPointer(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> memSeg -> loffset -> (arg)
        var start = new Label();
        var end = new Label();
        if (isArgument) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ... -> memSeg -> loffset
            mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex);
        }
        // -> ... -> memSeg -> loffset
        // -> ... -> modNewArg
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(MemoryAccess.class),
                "getAddressAtOffset",
                Type.getMethodDescriptor(Type.getType(MemoryAddress.class), Type.getType(MemorySegment.class), Type.getType(long.class)),
                false);
        // -> ... -> newArg
        constructPointerHelper(mw, type, currentLocalVarIndex, isArgument);
        // -> ... -> newArg
        mw.visitLabel(end);
        if (isArgument) {
            var argSig = new SignatureWriter();
            ClassGenerator.signature(argSig, type);
            mw.visitLocalVariable("arg",
                    Type.getDescriptor(Pointer.class),
                    argSig.toString(),
                    start, end,
                    currentLocalVarIndex);
        }
    }

    private static void constructPointerHelper(MethodVisitor mw, java.lang.reflect.Type type, int currentLocalVarIndex, boolean isArgument) {
        // -> ... -> modNewArg
        var pointedType = ((ParameterizedType) type).getActualTypeArguments()[0];
        var pointedRawType = Utils.rawType(pointedType);
        // -> ... -> modNewArg
        if (isArgument) {
            // -> ... -> modNewArg -> arg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
            // -> ... -> modNewArg -> pointedArg (uncasted)
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(Pointer.class),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            // -> ... -> modNewArg -> pointedArg
            mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(pointedRawType));
        }
        if (pointedRawType.isArray()) {
            // -> ... -> modNewArg -> (pointedArg)
            if (isArgument) {
                // -> ... -> modNewArg -> pointedArg
                java.lang.reflect.Type pointedComponentType;
                if (pointedType instanceof GenericArrayType g) {
                    pointedComponentType = g.getGenericComponentType();
                } else if (pointedType instanceof Class<?> c) {
                    pointedComponentType = c.componentType();
                } else {
                    throw new AssertionError("Unexpected type " + pointedType.getTypeName() + ", " + pointedType.getClass().getName());
                }
                var pointedComponentRawType = Utils.rawType(pointedComponentType);
                SizeData pointedComponentSizeAndData;
                if (Utils.isPrimitive(pointedComponentType)) {
                    pointedComponentSizeAndData = Utils.primitiveLayoutSize(pointedComponentType);
                } else if (pointedComponentRawType.isArray()) {
                    throw new AssertionError("Can't reach here");
                } else if (Utils.isStruct(pointedComponentType)) {
                    pointedComponentSizeAndData = Utils.recordLayoutSize(pointedComponentType);
                } else if (pointedComponentRawType == Pointer.class || MemoryAddress.class.isAssignableFrom(pointedComponentRawType) || pointedComponentRawType == String.class) {
                    pointedComponentSizeAndData = Utils.pointerLayoutSize();
                } else {
                    throw new AssertionError("Unexpected type " + pointedComponentType.getTypeName());
                }
                long size = pointedComponentSizeAndData.size();
                long sizePadding = Utils.align(size, pointedComponentSizeAndData.alignment());
                // -> ... -> pointedArg -> modNewArg -> pointedArg
                mw.visitInsn(Opcodes.DUP_X1);
                // -> ... -> pointedArg -> modNewArg -> pointedArg.len
                mw.visitInsn(Opcodes.ARRAYLENGTH);
                // -> ... -> pointedArg -> modNewArg -> pointedArg.len -> 1
                mw.visitInsn(Opcodes.ICONST_1);
                // -> ... -> pointedArg -> modNewArg -> pointedArg.len - 1
                mw.visitInsn(Opcodes.ISUB);
                // -> ... -> pointedArg -> modNewArg -> lpointedArg.len - 1
                mw.visitInsn(Opcodes.I2L);
                // -> ... -> pointedArg -> modNewArg -> lpointedArg.len - 1 -> lsizePadding
                mw.visitLdcInsn(sizePadding);
                // -> ... -> pointedArg -> modNewArg -> (lpointedArg.len - 1) * lsizePadding
                mw.visitInsn(Opcodes.LMUL);
                // -> ... -> pointedArg -> modNewArg -> (lpointedArg.len - 1) * lsizePadding -> lsize
                mw.visitLdcInsn(size);
                // -> ... -> pointedArg -> modNewArg -> lpointedSegSize
                mw.visitInsn(Opcodes.LADD);
            } else {
                throw new IllegalArgumentException("Can't construct array without known length");
            }
            // -> ... -> (pointedArg) -> modNewArg -> lpointedSegSize
        } else {
            // -> ... -> modNewArg -> (pointedArg)
            if (isArgument) {
                // -> ... -> modNewArg -> pointedArg
                // -> ... -> pointedArg -> modNewArg
                mw.visitInsn(Opcodes.SWAP);
            }
            long size;
            if (Utils.isPrimitive(pointedType)) {
                size = Utils.primitiveLayoutSize(pointedType).size();
            } else if (Utils.isStruct(pointedType)) {
                size = Utils.recordLayoutSize(pointedType).size();
            } else if (pointedRawType == Pointer.class || pointedRawType == String.class || MemoryAddress.class.isAssignableFrom(pointedRawType)) {
                size = CLinker.C_POINTER.byteSize();
            } else {
                throw new AssertionError("Unexpected type " + pointedType.getTypeName());
            }
            // -> ... -> (pointedArg) -> modNewArg -> lpointedSegSize
            mw.visitLdcInsn(size);
        }
        // -> ... -> (pointedArg) -> modNewArg -> lpointedSegSize
        // -> ... -> (pointedArg) -> pointedSeg
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(MemoryAddress.class),
                "asSegmentRestricted",
                Type.getMethodDescriptor(Type.getType(MemorySegment.class), Type.getType(long.class)),
                true);
        if (isArgument) {
            // -> ... -> pointedArg -> pointedSeg
            // -> ... -> pointedSeg -> pointedArg
            mw.visitInsn(Opcodes.SWAP);
        }
        // -> ... -> pointedSeg -> (pointedArg)
        // -> ... -> newPointedArg
        constructVariable(mw, pointedType, Optional.of(0L), Optional.empty(), isArgument ? currentLocalVarIndex + 1 : currentLocalVarIndex, isArgument);
        // -> ... -> newPointedArg -> newArg
        mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Pointer.class));
        // -> ... -> newArg -> newPointedArg -> newArg
        mw.visitInsn(Opcodes.DUP_X1);
        // -> ... -> newArg -> newArg -> newPointedArg
        mw.visitInsn(Opcodes.SWAP);
        // -> ... -> newArg
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                Type.getInternalName(Pointer.class),
                "<init>",
                Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Object.class)),
                false);
        // -> ... -> newArg
    }
}
