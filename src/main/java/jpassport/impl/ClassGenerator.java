package jpassport.impl;

import java.io.IOException;
import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jdk.incubator.foreign.*;
import jpassport.Passport;
import jpassport.annotations.ArrayValueArg;
import jpassport.annotations.RefArg;
import org.objectweb.asm.*;

public class ClassGenerator {
    public static <T extends Passport> T build(Class<T> interfaceKlass, MethodHandles.Lookup lookup, String libraryName) throws IOException, IllegalAccessException {
        var klassName = interfaceKlass.getSimpleName() + "Impl";
        var klassFullName = Type.getInternalName(interfaceKlass) + "Impl";
        if (!klassFullName.equals(interfaceKlass.getPackageName() + "." + klassName)) {
            throw new AssertionError(String.format("Class internal name %s, expected name %s",
                    klassFullName, interfaceKlass.getPackageName() + "." + klassName));
        }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V16,
                Opcodes.ACC_PUBLIC,
                klassName,
                null,
                "java/lang/Object",
                new String[] {interfaceKlass.getCanonicalName()});
        cw.visitModule(interfaceKlass.getModule().getName(), Opcodes.ACC_MANDATED, null);

        var methods = Arrays.stream(interfaceKlass.getMethods())
                .filter(method -> {
                    int modifier = method.getModifiers();
                    return ((modifier & Modifier.PUBLIC) != 0) && ((modifier & Modifier.STATIC) == 0);
                }).toList();

        {
            // This part declare the method handles that are used to called the native library
            for (var method : methods) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        method.getName(),
                        Type.getDescriptor(MethodHandle.class),
                        null,
                        null);
            }
        }

        classInitializer(cw, klassFullName, methods, libraryName);

        for (var method : methods) {
            methodImplementation(cw, method, klassFullName);
        }

        var createdKlass = lookup.defineHiddenClass(cw.toByteArray(), true).lookupClass();
        try {
            return (T) createdKlass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException | InstantiationException | InvocationTargetException e) {
            throw new RuntimeException("Unexpected error", e);
        }
    }

    /**
     * Parse the class initializer, the resulted bytecode is similar to that compiled from this piece of source code
     *
     * <blockquote><pre>{@code
     * static {
     *      var lib = LibraryLookup.ofLibrary(libraryName);
     *      var linker = CLinker.getInstance();
     *      HANDLE_NUM = linker.downcallHandle(lib.lookup(methodNumName),
     *              MethodType.methodType(methodNumReturnType, new Class<\?>[]\{methodNumArgType0, ...\}),
     *              FunctionDescriptor.of(methodNumReturnTypeDesc, new MemoryLayout[]{methodNumArgType0, ...});
     *
     *      or
     *
     *      HANDLE_NUM = linker.downcallHandle(lib.lookup(methodNumName),
     *              MethodType.methodType(void.class, new Class<\?>[]\{methodNumArgType0, ...\}),
     *              FunctionDescriptor.ofVoid(new MemoryLayout[]{methodNumArgType0, ...});
     * }
     * }</pre></blockquote>
     *
     * for each {@code num} correspond to a method declared in the binding interface
     *
     * @param cw The ClassWrite instance
     * @param klassFullName The full name (internal name) of the class being written
     * @param methods The list of methods declared in the binding interface
     * @param libraryName The name of the native library needed to be loaded
     */
    private static void classInitializer(ClassWriter cw, String klassFullName, List<Method> methods, String libraryName){
        var mw = cw.visitMethod(Opcodes.ACC_STATIC,
                "<clinit>",
                Type.getMethodDescriptor(Type.VOID_TYPE),
                null,
                null);
        var tryBlock = new Label();
        var catchBlock = new Label();
        var finish = new Label();
        mw.visitTryCatchBlock(tryBlock, catchBlock, catchBlock, Type.getInternalName(Exception.class));
        mw.visitLabel(tryBlock);
        // -> libName
        mw.visitLdcInsn(libraryName);
        // -> libLookup
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(LibraryLookup.class),
                "ofLibrary",
                Type.getMethodDescriptor(Type.getType(LibraryLookup.class), Type.getType(String.class)),
                true);
        // ->
        mw.visitVarInsn(Opcodes.ASTORE, 0);
        // -> clinker
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(CLinker.class),
                "getInstance",
                Type.getMethodDescriptor(Type.getType(CLinker.class)),
                true);

        for (var method : methods) {
            // -> clinker -> clinker
            mw.visitInsn(Opcodes.DUP);
            // -> clinker -> clinker -> libLookup
            mw.visitVarInsn(Opcodes.ALOAD, 0);
            // -> clinker -> clinker -> symbolOptional
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(LibraryLookup.class),
                    "lookup",
                    Type.getMethodDescriptor(Type.getType(Optional.class), Type.getType(String.class)),
                    true);
            // -> clinker -> clinker -> symbol (uncasted)
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(Optional.class),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            // -> clinker -> clinker -> symbol
            mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(LibraryLookup.Symbol.class));
            var modRetType = InternalUtils.wrapPrimitive(method.getReturnType(), method.isAnnotationPresent(RefArg.class));
            var modArgTypeList = Arrays.stream(method.getParameters())
                    .map(param -> InternalUtils.wrapPrimitive(param.getType(), param.isAnnotationPresent(RefArg.class))).toList();
            // -> clinker -> clinker -> symbol -> retType
            mw.visitLdcInsn(modRetType);
            if (modArgTypeList.size() == 0) {
                // -> clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class)),
                        false);
            } else if (modArgTypeList.size() == 1) {
                // -> clinker -> clinker -> symbol -> retType -> argType_0
                mw.visitLdcInsn(modArgTypeList.get(0));
                // -> clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class), Type.getType(Class.class)),
                        false);
            } else {
                // -> clinker -> clinker -> symbol -> retType -> argListSize
                mw.visitLdcInsn(modArgTypeList.size());
                // -> clinker -> clinker -> symbol -> retType -> argList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Class.class));
                for (int i = 0; i < modArgTypeList.size(); i++) {
                    // -> clinker -> clinker -> symbol -> retType -> argList -> argList
                    mw.visitInsn(Opcodes.DUP);
                    // -> clinker -> clinker -> symbol -> retType -> argList -> argList -> i
                    mw.visitLdcInsn(i);
                    // -> clinker -> clinker -> symbol -> retType -> argList -> argList -> i -> argType_i
                    mw.visitLdcInsn(modArgTypeList.get(i));
                    // -> clinker -> clinker -> symbol -> retType -> argList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // -> clinker -> clinker -> symbol -> retType -> argList
                // -> clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class), Type.getType(Class.class.arrayType())),
                        false);
            }

            // -> clinker -> clinker -> symbol -> methodType
            if (modRetType == void.class) {
                // -> clinker -> clinker -> symbol -> methodType -> argDesSize
                mw.visitLdcInsn(modArgTypeList.size());
                // -> clinker -> clinker -> symbol -> methodType -> argDesList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
                for (int i = 0; i < modArgTypeList.size(); i++) {
                    // -> clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList
                    mw.visitInsn(Opcodes.DUP);
                    // -> clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList -> i
                    mw.visitLdcInsn(i);
                    var param = method.getParameters()[i];
                    // -> clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList -> i -> argDesList[i]
                    getTypeDesc(mw, param.getType(), Optional.ofNullable(param.getAnnotation(RefArg.class)), Optional.ofNullable(param.getAnnotation(ArrayValueArg.class)));
                    // -> clinker -> clinker -> symbol -> methodType -> argDesList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // -> clinker -> clinker -> symbol -> methodType -> argDesList
                // -> clinker -> clinker -> symbol -> methodType -> funcDes
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(FunctionDescriptor.class),
                        "ofVoid",
                        Type.getMethodDescriptor(Type.getType(FunctionDescriptor.class), Type.getType(MemoryLayout.class.arrayType())),
                        false);
            } else {
                // -> clinker -> clinker -> symbol -> methodType -> retDes
                getTypeDesc(mw, method.getReturnType(), Optional.ofNullable(method.getAnnotation(RefArg.class)), Optional.ofNullable(method.getAnnotation(ArrayValueArg.class)));
                // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesSize
                mw.visitLdcInsn(modArgTypeList.size());
                // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
                for (int i = 0; i < modArgTypeList.size(); i++) {
                    // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList
                    mw.visitInsn(Opcodes.DUP);
                    // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList -> i
                    mw.visitLdcInsn(i);
                    var param = method.getParameters()[i];
                    // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList -> i -> argDesList[i]
                    getTypeDesc(mw, param.getType(), Optional.ofNullable(param.getAnnotation(RefArg.class)), Optional.ofNullable(param.getAnnotation(ArrayValueArg.class)));
                    // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                // -> clinker -> clinker -> symbol -> methodType -> funcDes
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(FunctionDescriptor.class),
                        "of",
                        Type.getMethodDescriptor(Type.getType(FunctionDescriptor.class), Type.getType(MemoryLayout.class), Type.getType(MemoryLayout.class.arrayType())),
                        false);
            }
            // -> clinker -> clinker -> symbol -> methodType -> funcDes
            // -> clinker -> methodHandle
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(CLinker.class),
                    "downcallHandle",
                    Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.getType(Addressable.class), Type.getType(MethodType.class), Type.getType(FunctionDescriptor.class)),
                    true);
            // -> clinker
            mw.visitFieldInsn(Opcodes.PUTSTATIC,
                    klassFullName,
                    method.getName(),
                    Type.getDescriptor(MethodHandle.class));
        }

        // -> clinker
        // ->
        mw.visitInsn(Opcodes.POP);
        mw.visitJumpInsn(Opcodes.GOTO, finish);
        // -> causeExcep
        mw.visitLabel(catchBlock);
        // ->
        mw.visitVarInsn(Opcodes.ASTORE, 0);
        // -> runExcep
        mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(RuntimeException.class));
        // -> runExcep -> runExcep
        mw.visitInsn(Opcodes.DUP);
        // -> runExcep -> runExcep -> causeExcep
        mw.visitVarInsn(Opcodes.ALOAD, 0);
        // -> runExcep
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                Type.getInternalName(RuntimeException.class),
                "<init>",
                Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Throwable.class)),
                false);
        // ->
        mw.visitInsn(Opcodes.ATHROW);
        mw.visitLabel(finish);
        mw.visitInsn(Opcodes.RETURN);
        mw.visitLocalVariable("lib",
                Type.getDescriptor(LibraryLookup.class),
                null,
                tryBlock, catchBlock,
                0);
        mw.visitLocalVariable("e",
                Type.getDescriptor(Exception.class),
                null,
                catchBlock, finish,
                0);
        mw.visitMaxs(0, 0);
        mw.visitEnd();
    }

    private static void methodImplementation(ClassWriter cw, Method method, String klassFullName) {
        // ->
        var mw = cw.visitMethod(Opcodes.ACC_PUBLIC,
                method.getName(),
                Type.getMethodDescriptor(method),
                null,
                null);
        for (var param : method.getParameters()) {
            mw.visitParameter(param.getName(), Opcodes.ACC_MANDATED);
        }
        var tryBlock = new Label();
        var catchBlock = new Label();
        var finallyBlock = new Label();
        int scopeLocalVarIndex;
        int paramStackSize = 1; // The first slot is "this"
        for (var paramType : method.getParameterTypes()) {
            if (paramType == long.class || paramType == double.class) {
                paramStackSize += 2;
            } else {
                paramStackSize += 1;
            }
        }
        scopeLocalVarIndex = paramStackSize;
        boolean needScope = method.getReturnType().isArray() || method.getReturnType().isRecord() || method.getAnnotation(RefArg.class) != null;
        needScope = needScope || Arrays.stream(method.getParameters()).anyMatch(c -> c.getType().isRecord() || c.getType().isArray() || c.getAnnotation(RefArg.class) != null);
        if (needScope) {
            // -> scope
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(NativeScope.class),
                    "unboundedScope",
                    Type.getMethodDescriptor(Type.getType(NativeScope.class)),
                    true);
            // ->
            mw.visitVarInsn(Opcodes.ASTORE, scopeLocalVarIndex);
        } else {
            scopeLocalVarIndex--;
        }
        // ->
        // -> handle
        mw.visitFieldInsn(Opcodes.GETSTATIC,
                klassFullName,
                method.getName(),
                Type.getDescriptor(MethodHandle.class));
        for (int i = 0, slot = 1; i < method.getParameterCount(); i++) {
            // -> ...
            var param = method.getParameters()[i];
            var paramType = param.getType();
            if (paramType == boolean.class || paramType == byte.class || paramType == short.class || paramType == char.class || paramType == int.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.ILOAD, slot);
                slot += 1;
            } else if (paramType == long.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.LLOAD, slot);
                slot += 2;
            } else if (paramType == float.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.FLOAD, slot);
                slot += 1;
            } else if (paramType == double.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.DLOAD, slot);
                slot += 2;
            } else {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.ALOAD, slot);
                slot += 1;
            }
            // -> ... -> arg_i
            // -> ... -> modArg_i
            resolveArgument(mw, param, scopeLocalVarIndex + 1, scopeLocalVarIndex);
        }
        // -> handle -> arg0 -> arg1 -> ...
        var retTypeWrapper = Type.getType(InternalUtils.wrapPrimitive(method.getReturnType(), method.isAnnotationPresent(RefArg.class)));
        var paramTypeWrapperList = Arrays.stream(method.getParameters())
                .map(param -> InternalUtils.wrapPrimitive(param.getType(), param.isAnnotationPresent(RefArg.class)))
                .map(Type::getType).toList().toArray(new Type[method.getParameterCount()]);
        // -> ret
        mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(MethodHandle.class),
                "invokeExact",
                Type.getMethodDescriptor(retTypeWrapper, paramTypeWrapperList),
                false);
        var retType = method.getReturnType();
        // -> (ret)
        if (retType == void.class) {
            // ->
            mw.visitInsn(Opcodes.RETURN);
        } else if (retType == boolean.class || retType == byte.class || retType == short.class || retType == char.class || retType == int.class) {
            // -> ret
            // ->
            mw.visitInsn(Opcodes.IRETURN);
        } else if (retType == long.class) {
            // -> ret
            // ->
            mw.visitInsn(Opcodes.LRETURN);
        } else if (retType == float.class) {
            // -> ret
            // ->
            mw.visitInsn(Opcodes.FRETURN);
        } else if (retType == double.class) {
            // -> ret
            // ->
            mw.visitInsn(Opcodes.DRETURN);
        } else {
            // -> ret
            // ->
            mw.visitInsn(Opcodes.ARETURN);
        }
        mw.visitMaxs(0, 0);
        mw.visitEnd();
    }

    private static void resolveArgument(MethodVisitor mw, Parameter arg, int currentLocalVarIndex, final int scopeLocalVarIndex) {
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
                    long length = Optional.ofNullable(arg.getAnnotation(ArrayValueArg.class)).map(ArrayValueArg::length).orElseThrow(() -> new RuntimeException("Missing ArrayValuerecord annotation"));
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
            }
        }
    }

    private static void resolveResult(MethodVisitor mw, Class<?> retType, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... ret

    }

    private static void deconstructVariable(MethodVisitor mw, Class<?> varType, Optional<Long> currentOffset, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> arg with nonconstant offset
        // -> ... -> memSeg -> arg -> with constant offset
        currentOffset.ifPresent(l -> {
            // -> ... -> memSeg -> arg
            if (!varType.isRecord()) {
                // -> ... -> memSeg -> arg -> loffset
                mw.visitLdcInsn(l);
                if (varType == long.class || varType == double.class) {
                    // -> ... -> memSeg -> loffset -> arg
                    mw.visitInsn(Opcodes.DUP2_X2);
                } else {
                    // -> ... -> memSeg -> loffset -> arg
                    mw.visitInsn(Opcodes.DUP2_X1);
                }
            }
        });
        // -> ... -> memSeg -> loffset -> arg (except with constant offset and record)
        if (varType == boolean.class) {
            // -> ... -> memSeg -> loffset -> (byte)arg
            mw.visitInsn(Opcodes.I2B);
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setIntAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(int.class)),
                    false);
        } else if (varType == byte.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setByteAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(byte.class)),
                    false);
        } else if (varType == short.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setShortAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(short.class)),
                    false);
        } else if (varType == char.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setCharAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(char.class)),
                    false);
        } else if (varType == int.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setIntAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(int.class)),
                    false);
        } else if (varType == long.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setLongAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(long.class)),
                    false);
        } else if (varType == float.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setFloatAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(float.class)),
                    false);
        } else if (varType == double.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setDoubleAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(double.class)),
                    false);
        } else if (varType == MemoryAddress.class) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setAddressAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(MemoryAddress.class)),
                    false);
        } else if (varType.isArray()) {
            // -> ... -> memSeg -> loffset -> arg
            // -> ...
            deconstructArray(mw, varType, currentLocalVarIndex, scopeLocalVarIndex);
        } else {
            // -> ... -> memSeg -> loffset -> arg with nonconstant offset
            // -> ... -> memSeg -> arg -> with constant offset
            deconstructRecord(mw, varType, currentOffset, currentLocalVarIndex, scopeLocalVarIndex);
        }
    }

    private static void deconstructArray(MethodVisitor mw, Class<?> varType, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> arg
        var methodStart = new Label();
        var forStart = new Label();
        var forEnd = new Label();
        var componentType = varType.componentType();
        mw.visitLabel(methodStart);
        // -> ... .> memSeg -> loffset
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex + 3);
        // -> ... -> memSeg
        mw.visitVarInsn(Opcodes.LSTORE, currentLocalVarIndex + 1);
        // -> ...
        mw.visitVarInsn(Opcodes.ASTORE, currentLocalVarIndex);
        // -> ... -> arg
        mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 3);
        // -> ... -> arg.len
        mw.visitInsn(Opcodes.ARRAYLENGTH);
        // -> ... -> arg.len -> 0
        mw.visitInsn(Opcodes.ICONST_0);
        mw.visitLabel(forStart);
        // -> ... -> arg.len -> index -> arg.len -> index
        mw.visitInsn(Opcodes.DUP2);
        // -> ... -> arg.len -> index -> arg.len - index
        mw.visitInsn(Opcodes.ISUB);
        // -> ... -> arg.len -> index
        mw.visitJumpInsn(Opcodes.IFLE, forEnd);
        // -> ... -> arg.len -> index -> index
        mw.visitInsn(Opcodes.DUP);
        // -> ... -> arg.len -> index
        mw.visitVarInsn(Opcodes.ISTORE, currentLocalVarIndex + 4);
        if (!componentType.isArray()) {
            // -> ... -> arg.len -> index
            var sizeData = componentType.isRecord() ? InternalUtils.recordLayoutSize(componentType) : InternalUtils.primitiveLayoutSize(componentType);
            long elementSize = InternalUtils.align(sizeData.size(), sizeData.alignment());
            // -> ... -> arg.len -> index -> index
            mw.visitInsn(Opcodes.DUP);
            // -> ... -> arg.len -> index -> lindex
            mw.visitInsn(Opcodes.I2L);
            // -> ... -> arg.len -> index -> lindex -> elementSize
            mw.visitLdcInsn(elementSize);
            // -> ... -> arg.len -> index -> larrayOffset
            mw.visitInsn(Opcodes.LMUL);
            // -> ... -> arg.len -> index -> larrayOffset -> lbaseOffset
            mw.visitVarInsn(Opcodes.LLOAD, currentLocalVarIndex + 1);
            // -> ... -> arg.len -> index -> loffset
            mw.visitInsn(Opcodes.LADD);
            // -> ... -> arg.len -> index -> loffset -> memSeg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
            // -> ... -> arg.len -> index -> memSeg -> loffset -> memSeg
            mw.visitInsn(Opcodes.DUP_X2);
            // -> ... -> arg.len -> index -> memSeg -> loffset
            mw.visitInsn(Opcodes.POP);
            // -> ... -> arg.len -> index -> memSeg -> loffset -> arg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 3);
            // -> ... -> arg.len -> index -> memSeg -> loffset -> arg -> index
            mw.visitVarInsn(Opcodes.ILOAD, currentLocalVarIndex + 4);
            if (componentType == byte.class) {
                mw.visitInsn(Opcodes.BALOAD);
            } else if (componentType == short.class) {
                mw.visitInsn(Opcodes.SALOAD);
            } else if (componentType == char.class) {
                mw.visitInsn(Opcodes.CALOAD);
            } else if (componentType == int.class) {
                mw.visitInsn(Opcodes.IALOAD);
            } else if (componentType == long.class) {
                mw.visitInsn(Opcodes.LALOAD);
            } else if (componentType == float.class) {
                mw.visitInsn(Opcodes.FALOAD);
            } else if (componentType == double.class) {
                mw.visitInsn(Opcodes.AALOAD);
            }
            // -> ... -> arg.len -> index -> memSeg -> loffset -> arg[index]
            // -> ... -> arg.len -> index
            deconstructVariable(mw, componentType, Optional.empty(), currentLocalVarIndex + 5, scopeLocalVarIndex);
            // -> ... -> arg.len
            mw.visitInsn(Opcodes.POP);
        } else {
            // -> ... -> arg.len -> index
            // -> ... -> arg.len -> index -> arg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex + 3);
            // -> ... -> arg.len -> arg -> index
            mw.visitInsn(Opcodes.SWAP);
            // -> ... -> arg.len -> arg[index]
            mw.visitInsn(Opcodes.AALOAD);
            // -> ... -> arg.len -> arg[index] -> arg[index]
            mw.visitInsn(Opcodes.DUP);
            // -> ... -> arg.len -> arg[index] -> arg[index] -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            // -> ... -> arg.len -> arg[index] -> scope -> arg[index]
            mw.visitInsn(Opcodes.SWAP);
            // -> ... -> arg.len -> arg[index] -> scope -> arg[index].len
            mw.visitInsn(Opcodes.ARRAYLENGTH);
            // -> ... -> arg.len -> arg[index] -> elementSeg
            allocateArray(mw, componentType);
            // -> ... -> arg.len -> elementSegAddr
            initializeAllocatedObject(mw, componentType, currentLocalVarIndex + 5, scopeLocalVarIndex);
            // -> ... -> arg.len -> elementSegAddr -> memSeg
            mw.visitVarInsn(Opcodes.ALOAD, currentLocalVarIndex);
            // -> ... -> arg.len -> memSeg -> elementSegAddr
            mw.visitInsn(Opcodes.SWAP);
            // -> ... -> arg.len -> memSeg -> elementSegAddr -> index
            mw.visitVarInsn(Opcodes.ILOAD, currentLocalVarIndex + 4);
            // -> ... -> arg.len -> memSeg -> elementSegAddr -> lindex
            mw.visitInsn(Opcodes.I2L);
            // -> ... -> arg.len -> memSeg -> elementSegAddr -> lindex -> pointerSize
            mw.visitLdcInsn(CLinker.C_POINTER.byteSize());
            // -> ... -> arg.len -> memSeg -> elementSegAddr -> loffset
            mw.visitInsn(Opcodes.LMUL);
            // -> ... -> arg.len -> memSeg -> loffset -> elementSegAddr -> loffset
            mw.visitInsn(Opcodes.DUP2_X1);
            // -> ... -> arg.len -> memSeg -> loffset -> elementSegAddr
            mw.visitInsn(Opcodes.POP2);
            // -> ... -> arg.len
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryAccess.class),
                    "setAddressAtOffset",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(MemoryAddress.class)),
                    false);
        }
        // -> ... -> arg.len
        mw.visitIincInsn(currentLocalVarIndex + 4, 1);
        // -> ... -> arg.len -> index + 1
        mw.visitVarInsn(Opcodes.ILOAD, currentLocalVarIndex + 4);
        // -> ... -> arg.len -> index + 1
        mw.visitJumpInsn(Opcodes.GOTO, forStart);
        mw.visitLabel(forEnd);
        // -> ...
        mw.visitInsn(Opcodes.DUP2);
        mw.visitLocalVariable("memSeg",
                Type.getDescriptor(MemorySegment.class),
                null,
                methodStart, forEnd,
                currentLocalVarIndex);
        mw.visitLocalVariable("baseOffset",
                Type.getDescriptor(long.class),
                null,
                methodStart, forEnd,
                currentLocalVarIndex + 1);
        mw.visitLocalVariable("arg",
                Type.getDescriptor(varType),
                null,
                methodStart, forEnd,
                currentLocalVarIndex + 3);
        mw.visitLocalVariable("i",
                Type.getDescriptor(int.class),
                null,
                methodStart, forEnd,
                currentLocalVarIndex + 4);
        // -> ...
    }

    private static void deconstructRecord(MethodVisitor mw, Class<?> varType, Optional<Long> currentOffset, int currentLocalVarIndex, final int scopeLocalVarIndex) {
        // -> ... -> memSeg -> loffset -> record       with nonconstant offset
        // -> ... -> memSeg -> record                  with constant offset
        var methodStart = new Label();
        var methodEnd = new Label();
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
            if (component.isAnnotationPresent(RefArg.class)) {
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
                initializeAllocatedObject(mw, componentType, currentOffset.isPresent() ? currentLocalVarIndex + 4 : currentLocalVarIndex + 2, scopeLocalVarIndex);
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
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(MemorySegment.class), Type.getType(long.class), Type.getType(MemoryAddress.class)),
                        false);

                relOffset += CLinker.C_POINTER.byteSize();
            } else {
                // -> ...
                long size;
                long align;
                if (componentType.isArray()) {
                    int length = Optional.ofNullable(component.getAnnotation(ArrayValueArg.class)).map(ArrayValueArg::length).orElseThrow(() -> new RuntimeException("Missing ArrayValuerecord annotation"));
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
                deconstructVariable(mw, componentType, currentOffset.map(l -> l + tempOffset), currentOffset.isPresent() ? currentLocalVarIndex + 4 : currentLocalVarIndex + 2, scopeLocalVarIndex);
                relOffset += size;
            }
        }
        // -> ...
        mw.visitLabel(methodEnd);

        mw.visitLocalVariable("memSeg",
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
        // -> ... -> argSeg
        deconstructVariable(mw, type, Optional.of(0L), currentLocalVarIndex, scopeLocalVarIndex);
        // -> ... -> argSegAddr
        mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                Type.getInternalName(MemorySegment.class),
                "address",
                Type.getMethodDescriptor(Type.getType(MemoryAddress.class)),
                true);
    }

    private static void getTypeDesc(MethodVisitor mw, Class<?> paramType, Optional<RefArg> refArg, Optional<ArrayValueArg> arrayValueArg) {
        // -> ...
        var modType = InternalUtils.wrapPrimitive(paramType, refArg.isPresent());
        if (modType != MemorySegment.class) {
            // -> ...
            // -> ... -> paramDesc
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(CLinker.class),
                    InternalUtils.cDescriptorName(modType),
                    Type.getDescriptor(ValueLayout.class));
        } else {
            // -> ...
            long size;
            if (paramType.isArray()) {
                size = InternalUtils.arrayLayoutSize(paramType, arrayValueArg
                        .map(ArrayValueArg::length)
                        .orElseThrow(() ->
                                new RuntimeException("Array type needs to be annotated by either " + ArrayValueArg.class.getSimpleName() + " or " + RefArg.class.getSimpleName())
                        )).size();
            } else if (paramType.isRecord()) {
                size = InternalUtils.recordLayoutSize(paramType).size();
            } else {
                throw new AssertionError("Unexpected type " + paramType.getSimpleName());
            }
            // -> ... -> paramDesc.size
            mw.visitLdcInsn(size * 8);
            // -> ... -> paramDesc.size -> nativeByteOrder
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(ByteOrder.class),
                    "nativeOrder",
                    Type.getMethodDescriptor(Type.getType(ByteOrder.class)),
                    false);
            // -> ... -> paramDesc
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryLayout.class),
                    "ofValueBits",
                    Type.getMethodDescriptor(Type.getType(ValueLayout.class), Type.getType(long.class), Type.getType(ByteOrder.class)),
                    true);
        }
    }
}
