package jpassport.impl;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import jdk.incubator.foreign.*;

import org.objectweb.asm.*;
import jpassport.Passport;
import jpassport.annotations.ArrayValueArg;
import jpassport.annotations.RefArg;

public class ClassGenerator {
    public static <T extends Passport> T build(Class<T> interfaceKlass, MethodHandles.Lookup lookup, String libraryName) throws IOException, IllegalAccessException {
        var klassName = interfaceKlass.getSimpleName() + "Impl";
        var klassFullName = Type.getInternalName(interfaceKlass) + "Impl";
        if (!klassFullName.equals((interfaceKlass.getPackageName() + "." + klassName).replace('.', '/'))) {
            throw new AssertionError(String.format("Class internal name %s, expected name %s",
                    klassFullName, interfaceKlass.getPackageName() + "." + klassName));
        }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V16,
                Opcodes.ACC_PUBLIC,
                klassFullName,
                null,
                Type.getInternalName(Object.class),
                new String[] {Type.getInternalName(interfaceKlass)});

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

        // This part define the default constructor
        {
            var mw = cw.visitMethod(Opcodes.ACC_PUBLIC,
                    "<init>",
                    Type.getMethodDescriptor(Type.getType(void.class)),
                    null,
                    null);
            // -> this
            mw.visitVarInsn(Opcodes.ALOAD, 0);
            // ->
            mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Object.class),
                    "<init>",
                    Type.getMethodDescriptor(Type.getType(void.class)),
                    false);
            // ->
            mw.visitInsn(Opcodes.RETURN);
            mw.visitMaxs(0, 0);
            mw.visitEnd();
        }

        classInitializer(cw, klassFullName, methods, libraryName);

        for (var method : methods) {
            methodImplementation(cw, method, klassFullName);
        }
        cw.visitEnd();

        Files.write(Path.of("./out", klassName + ".class"), cw.toByteArray());

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
        // -> libLookup -> clinker
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(CLinker.class),
                "getInstance",
                Type.getMethodDescriptor(Type.getType(CLinker.class)),
                true);

        for (var method : methods) {
            // -> libLookup -> clinker
            // -> libLookup -> clinker -> libLookup -> clinker
            mw.visitInsn(Opcodes.DUP2);
            // -> libLookup -> clinker -> clinker -> libLookup
            mw.visitInsn(Opcodes.SWAP);
            // -> libLookup -> clinker -> clinker -> libLookup -> methodName
            mw.visitLdcInsn(method.getName());
            // -> libLookup -> clinker -> clinker -> symbolOptional
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(LibraryLookup.class),
                    "lookup",
                    Type.getMethodDescriptor(Type.getType(Optional.class), Type.getType(String.class)),
                    true);
            // -> libLookup -> clinker -> clinker -> symbol (uncasted)
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(Optional.class),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            // -> libLookup -> clinker -> clinker -> symbol
            mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(LibraryLookup.Symbol.class));
            var modRetType = InternalUtils.wrapPrimitive(method.getReturnType(), method.isAnnotationPresent(RefArg.class));
            var modArgTypeList = Arrays.stream(method.getParameters())
                    .map(param -> InternalUtils.wrapPrimitive(param.getType(), param.isAnnotationPresent(RefArg.class))).toList();
            // -> libLookup -> clinker -> clinker -> symbol -> retType
            getKlassMirror(mw, modRetType);
            if (modArgTypeList.size() == 0) {
                // -> libLookup -> clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class)),
                        false);
            } else if (modArgTypeList.size() == 1) {
                // -> libLookup -> clinker -> clinker -> symbol -> retType -> argType_0
                getKlassMirror(mw, modArgTypeList.get(0));
                // -> libLookup -> clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class), Type.getType(Class.class)),
                        false);
            } else {
                // -> libLookup -> clinker -> clinker -> symbol -> retType -> argListSize
                mw.visitLdcInsn(modArgTypeList.size());
                // -> libLookup -> clinker -> clinker -> symbol -> retType -> argList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Class.class));
                for (int i = 0; i < modArgTypeList.size(); i++) {
                    // -> libLookup -> clinker -> clinker -> symbol -> retType -> argList -> argList
                    mw.visitInsn(Opcodes.DUP);
                    // -> libLookup -> clinker -> clinker -> symbol -> retType -> argList -> argList -> i
                    mw.visitLdcInsn(i);
                    // -> libLookup -> clinker -> clinker -> symbol -> retType -> argList -> argList -> i -> argType_i
                    getKlassMirror(mw, modArgTypeList.get(i));
                    // -> libLookup -> clinker -> clinker -> symbol -> retType -> argList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // -> libLookup -> clinker -> clinker -> symbol -> retType -> argList
                // -> libLookup -> clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class), Type.getType(Class.class.arrayType())),
                        false);
            }

            // -> libLookup -> clinker -> clinker -> symbol -> methodType
            if (modRetType == void.class) {
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesSize
                mw.visitLdcInsn(modArgTypeList.size());
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
                for (int i = 0; i < modArgTypeList.size(); i++) {
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList
                    mw.visitInsn(Opcodes.DUP);
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList -> i
                    mw.visitLdcInsn(i);
                    var param = method.getParameters()[i];
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList -> i -> argDesList[i]
                    getTypeDesc(mw, param.getType(), Optional.ofNullable(param.getAnnotation(RefArg.class)), Optional.ofNullable(param.getAnnotation(ArrayValueArg.class)));
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> argDesList
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> funcDes
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(FunctionDescriptor.class),
                        "ofVoid",
                        Type.getMethodDescriptor(Type.getType(FunctionDescriptor.class), Type.getType(MemoryLayout.class.arrayType())),
                        false);
            } else {
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes
                getTypeDesc(mw, method.getReturnType(), Optional.ofNullable(method.getAnnotation(RefArg.class)), Optional.ofNullable(method.getAnnotation(ArrayValueArg.class)));
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesSize
                mw.visitLdcInsn(modArgTypeList.size());
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
                for (int i = 0; i < modArgTypeList.size(); i++) {
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList
                    mw.visitInsn(Opcodes.DUP);
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList -> i
                    mw.visitLdcInsn(i);
                    var param = method.getParameters()[i];
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList -> i -> argDesList[i]
                    getTypeDesc(mw, param.getType(), Optional.ofNullable(param.getAnnotation(RefArg.class)), Optional.ofNullable(param.getAnnotation(ArrayValueArg.class)));
                    // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                // -> libLookup -> clinker -> clinker -> symbol -> methodType -> funcDes
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(FunctionDescriptor.class),
                        "of",
                        Type.getMethodDescriptor(Type.getType(FunctionDescriptor.class), Type.getType(MemoryLayout.class), Type.getType(MemoryLayout.class.arrayType())),
                        false);
            }

            // -> libLookup -> clinker -> clinker -> symbol -> methodType -> funcDes
            // -> libLookup -> clinker -> methodHandle
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(CLinker.class),
                    "downcallHandle",
                    Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.getType(Addressable.class), Type.getType(MethodType.class), Type.getType(FunctionDescriptor.class)),
                    true);
            // -> libLookup -> clinker
            mw.visitFieldInsn(Opcodes.PUTSTATIC,
                    klassFullName,
                    method.getName(),
                    Type.getDescriptor(MethodHandle.class));
        }

        // -> libLookup -> clinker
        // ->
        mw.visitInsn(Opcodes.POP2);
        mw.visitJumpInsn(Opcodes.GOTO, finish);
        mw.visitLabel(catchBlock);
        // -> causeExcep
        // -> causeExcep -> runExcep
        mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(RuntimeException.class));
        // -> runExcep -> causeExcep -> runExcep
        mw.visitInsn(Opcodes.DUP_X1);
        // -> runExcep -> runExcep -> causeExcep
        mw.visitInsn(Opcodes.SWAP);
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

        var userTryBlock = new Label();
        var userCatchBlock = new Label();
        var endBlock = new Label();

        int scopeLocalVarIndex;
        int firstLocalSlot = 1; // The first slot is "this"
        for (var paramType : method.getParameterTypes()) {
            if (paramType == long.class || paramType == double.class) {
                firstLocalSlot += 2;
            } else {
                firstLocalSlot += 1;
            }
        }
        boolean needScope = !method.getReturnType().isPrimitive() || method.isAnnotationPresent(RefArg.class);
        needScope = needScope || Arrays.stream(method.getParameters()).anyMatch(c -> !c.getType().isPrimitive() || c.isAnnotationPresent(RefArg.class));
        if (needScope) {
            scopeLocalVarIndex = firstLocalSlot;
            firstLocalSlot += 2;
            var modRetType = InternalUtils.wrapPrimitive(method.getReturnType(), method.isAnnotationPresent(RefArg.class));
            if (modRetType == double.class || modRetType == long.class) {
                firstLocalSlot += 2;
            } else if (modRetType != void.class) {
                firstLocalSlot += 1;
            }
        } else {
            scopeLocalVarIndex = -1;
        }

        // ->
        mw.visitTryCatchBlock(userTryBlock, userCatchBlock, userCatchBlock, Type.getInternalName(Throwable.class));
        {
            // ->
            mw.visitLabel(userTryBlock);
            if (needScope) {
                // ->
                scopeFulTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
            } else {
                // ->
                scopeLessTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
            }
            mw.visitJumpInsn(Opcodes.GOTO, endBlock);
        }

        {
            // -> throwable
            mw.visitLabel(userCatchBlock);
            // -> throwable -> runExcep
            mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(RuntimeException.class));
            // -> runExcep -> throwable -> runExcep
            mw.visitInsn(Opcodes.DUP_X1);
            // -> runExcep -> runExcep -> throwable
            mw.visitInsn(Opcodes.SWAP);
            // -> runExcep
            mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    Type.getInternalName(RuntimeException.class),
                    "<init>",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Throwable.class)),
                    false);
            // ->
            mw.visitInsn(Opcodes.ATHROW);
        }
        mw.visitLabel(endBlock);

        mw.visitMaxs(0, 0);
        mw.visitEnd();
    }

    private static void scopeLessTryBlock(MethodVisitor mw, Method method, String klassFullName, int firstLocalSlot, final int scopeLocalVarIndex) {
        // ->
        // -> (ret)
        resourceTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
        // ->
        returnBlock(mw, method, firstLocalSlot);
    }

    private static void scopeFulTryBlock(MethodVisitor mw, Method method, String klassFullName, int firstLocalSlot, final int scopeLocalVarIndex) {
        // ->
        var userTryBlock = new Label();
        var resourceTryBlock = new Label();
        var resourceCatchBlock = new Label();
        var userCatchBlock = new Label();
        var modRetType = InternalUtils.wrapPrimitive(method.getReturnType(), method.isAnnotationPresent(RefArg.class));
        // ->
        mw.visitLabel(userTryBlock);
        // -> scope
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(NativeScope.class),
                "unboundedScope",
                Type.getMethodDescriptor(Type.getType(NativeScope.class)),
                true);
        // ->
        mw.visitVarInsn(Opcodes.ASTORE, scopeLocalVarIndex);
        // -> null
        mw.visitInsn(Opcodes.ACONST_NULL);
        // ->
        mw.visitVarInsn(Opcodes.ASTORE, scopeLocalVarIndex + 1);
        // ->
        mw.visitTryCatchBlock(resourceTryBlock, resourceCatchBlock, resourceCatchBlock, Type.getInternalName(Throwable.class));

        {
            // ->
            mw.visitLabel(resourceTryBlock);
            // -> (ret)
            resourceTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
            // -> (ret)
            if (modRetType == byte.class || modRetType == boolean.class || modRetType == short.class || modRetType == char.class || modRetType == int.class) {
                mw.visitVarInsn(Opcodes.ISTORE, scopeLocalVarIndex + 2);
            } else if (modRetType == long.class) {
                mw.visitVarInsn(Opcodes.LSTORE, scopeLocalVarIndex + 2);
            } else if (modRetType == float.class) {
                mw.visitVarInsn(Opcodes.FSTORE, scopeLocalVarIndex + 2);
            } else if (modRetType == double.class) {
                mw.visitVarInsn(Opcodes.DSTORE, scopeLocalVarIndex + 2);
            } else if (modRetType != void.class) {
                mw.visitVarInsn(Opcodes.ASTORE, scopeLocalVarIndex + 2);
            }
            // ->
            // ->
            resourceFinallyBlock(mw, scopeLocalVarIndex);
            // -> (ret)
            if (modRetType == byte.class || modRetType == boolean.class || modRetType == short.class || modRetType == char.class || modRetType == int.class) {
                mw.visitVarInsn(Opcodes.ILOAD, scopeLocalVarIndex + 2);
            } else if (modRetType == long.class) {
                mw.visitVarInsn(Opcodes.LLOAD, scopeLocalVarIndex + 2);
            } else if (modRetType == float.class) {
                mw.visitVarInsn(Opcodes.FLOAD, scopeLocalVarIndex + 2);
            } else if (modRetType == double.class) {
                mw.visitVarInsn(Opcodes.DLOAD, scopeLocalVarIndex + 2);
            } else if (modRetType != void.class) {
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex + 2);
            }
            // ->
            returnBlock(mw, method, firstLocalSlot);
        }

        {
            // -> throwable
            mw.visitLabel(resourceCatchBlock);
            // ->
            mw.visitVarInsn(Opcodes.ASTORE, scopeLocalVarIndex + 1);
            // ->
            resourceFinallyBlock(mw, scopeLocalVarIndex);
            // -> throwable
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex + 1);
            // ->
            mw.visitInsn(Opcodes.ATHROW);
        }
        // ->
        mw.visitLabel(userCatchBlock);

        mw.visitLocalVariable("scope",
                Type.getDescriptor(NativeScope.class),
                null,
                userTryBlock, userCatchBlock,
                scopeLocalVarIndex);
        mw.visitLocalVariable("primaryExcep",
                Type.getDescriptor(Throwable.class),
                null,
                userTryBlock, userCatchBlock,
                scopeLocalVarIndex + 1);
        if (modRetType != void.class) {
            mw.visitLocalVariable("rawReturnValue",
                    Type.getDescriptor(modRetType),
                    null,
                    userTryBlock, userCatchBlock,
                    scopeLocalVarIndex + 2);
        }
    }

    private static void resourceFinallyBlock(MethodVisitor mw, final int scopeLocalVarIndex) {
        // ->
        var elseBlock = new Label();
        var endBlock = new Label();
        var suppressTryBlock = new Label();
        var suppressCatchBlock = new Label();
        // ->
        // -> throwable
        mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex + 1);
        // if
        // ->
        mw.visitJumpInsn(Opcodes.IFNULL, elseBlock);
        {
            // ->
            mw.visitTryCatchBlock(suppressTryBlock, suppressCatchBlock, suppressCatchBlock, Type.getInternalName(Throwable.class));
            // try
            {
                // ->
                mw.visitLabel(suppressTryBlock);
                // -> scope
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
                // ->
                mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        Type.getInternalName(NativeScope.class),
                        "close",
                        Type.getMethodDescriptor(Type.getType(void.class)),
                        true);
                mw.visitJumpInsn(Opcodes.GOTO, endBlock);
            }
            // catch (Throwable secondThrowable)
            {
                // -> secondThrowable
                mw.visitLabel(suppressCatchBlock);
                // -> secondThrowable -> throwable
                mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex + 1);
                // -> throwable -> secondThrowable
                mw.visitInsn(Opcodes.SWAP);
                // ->
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(Throwable.class),
                        "addSuppressed",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Throwable.class)),
                        false);
                // ->
                mw.visitJumpInsn(Opcodes.GOTO, endBlock);
            }
        }
        // else
        {
            // ->
            mw.visitLabel(elseBlock);
            // -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            // ->
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(NativeScope.class),
                    "close",
                    Type.getMethodDescriptor(Type.getType(void.class)),
                    true);
        }
        // ->
        mw.visitLabel(endBlock);
    }

    private static void resourceTryBlock(MethodVisitor mw, Method method, String klassFullName, int firstLocalSlot, final int scopeLocalVarIndex) {
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
            ArgumentResolver.resolveArgument(mw, param, firstLocalSlot, scopeLocalVarIndex);
        }
        // -> handle -> arg0 -> arg1 -> ...
        var retTypeWrapper = Type.getType(InternalUtils.wrapPrimitive(method.getReturnType(), method.isAnnotationPresent(RefArg.class)));
        var paramTypeWrapperList = Arrays.stream(method.getParameters())
                .map(param -> InternalUtils.wrapPrimitive(param.getType(), param.isAnnotationPresent(RefArg.class)))
                .map(Type::getType).toList().toArray(new Type[method.getParameterCount()]);
        // -> (ret)
        mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(MethodHandle.class),
                "invokeExact",
                Type.getMethodDescriptor(retTypeWrapper, paramTypeWrapperList),
                false);
    }

    private static void returnBlock(MethodVisitor mw, Method method, int firstLocalSlot) {
        // -> (ret)
        var retType = method.getReturnType();
        if (!retType.isPrimitive()) {
            ResultResolver.resolveResult(mw, method, firstLocalSlot);
        }
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
    }

    private static void getKlassMirror(MethodVisitor mw, Class<?> type) {
        // -> ...
        if (type.isPrimitive()) {
            // -> ...
            var wrapper = MethodType.methodType(type).wrap().returnType();
            // -> klass
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(wrapper),
                    "TYPE",
                    Type.getDescriptor(Class.class));
        } else {
            // -> ...
            // -> klass
            mw.visitLdcInsn(Type.getType(type));
        }
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
                        .map(ArrayValueArg::arrayLength)
                        .orElseThrow(() ->
                                new RuntimeException("Array argument needs to be annotated by either " + ArrayValueArg.class.getSimpleName() + " or " + RefArg.class.getSimpleName())
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
