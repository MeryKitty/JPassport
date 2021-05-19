package jpassport.impl;

import jdk.incubator.foreign.*;
import jpassport.NativeLong;
import jpassport.Passport;
import jpassport.Pointer;
import jpassport.annotations.Layout;
import jpassport.annotations.Length;
import jpassport.annotations.NoSideEffect;
import org.objectweb.asm.Type;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class ClassGenerator {
    public static <T extends Passport> T build(Class<T> interfaceKlass, MethodHandles.Lookup lookup, String libraryName) throws IllegalAccessException {
        var klassName = interfaceKlass.getSimpleName() + "Impl";
        var klassFullName = interfaceKlass.getPackageName().replace('.', '/') + '/' + klassName;
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
            // #for (method : interfaceKlass.methods()) {
            //     private static final MethodHandle methodName;
            // }
            for (var method : methods) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        method.getName(),
                        Type.getDescriptor(MethodHandle.class),
                        null,
                        null);
            }
        }

        // This part define the default constructor
        // public KlassName() {}
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

        try {
            Files.write(Path.of("./out", klassName + ".class"), cw.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }

        var createdKlass = lookup.defineHiddenClass(cw.toByteArray(), true).lookupClass();
        try {
            @SuppressWarnings("unchecked")
            var result = (T) createdKlass.getDeclaredConstructor().newInstance();
            return result;
        } catch (NoSuchMethodException | InstantiationException | InvocationTargetException e) {
            throw new AssertionError("Unexpected error", e);
        }
    }

    /**
     * Parse the class initializer, the resulted bytecode is similar to that compiled from this piece of source code
     *
     * <blockquote><pre>{@code
     * static {
     *      var lib = LibraryLookup.ofLibrary(libraryName);
     *      var linker = CLinker.getInstance();
     *
     *      #for (method : interfaceKlass.methods()) {
     *          HANDLE_NUM = linker.downcallHandle(lib.lookup(methodName),
     *                  MethodType.methodType(methodRetType, new Class<\?>[]{methodArgType0, ...}),
     *                  FunctionDescriptor.of(methodRetDesc, new MemoryLayout[]{methodArgDesc0, ...});
     *
     *          or
     *
     *          HANDLE_NUM = linker.downcallHandle(lib.lookup(methodName),
     *                  MethodType.methodType(void.class, new Class<\?>[]{methodArgType0, ...}),
     *                  FunctionDescriptor.ofVoid(new MemoryLayout[]{methodArgDesc0, ...});
     *      }
     * }
     * }</pre></blockquote>
     *
     * @param cw The ClassWriter instance
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
        var endTryCatchBlock = new Label();
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
            var modRetType = InternalUtils.wrapPrimitive(method.getAnnotatedReturnType());
            var modArgTypeList = Arrays.stream(method.getParameters())
                    .map(param -> InternalUtils.wrapPrimitive(param.getAnnotatedType())).toList();
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
                    getTypeDesc(mw, param.getAnnotatedType());
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
                getTypeDesc(mw, method.getAnnotatedReturnType());
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
                    getTypeDesc(mw, param.getAnnotatedType());
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
        mw.visitJumpInsn(Opcodes.GOTO, endTryCatchBlock);
        mw.visitLabel(catchBlock);
        // -> causeExc
        // -> causeExc -> runExc
        mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(RuntimeException.class));
        // -> runExc -> causeExc -> runExc
        mw.visitInsn(Opcodes.DUP_X1);
        // -> runExc -> runExc -> causeExc
        mw.visitInsn(Opcodes.SWAP);
        // -> runExc
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                Type.getInternalName(RuntimeException.class),
                "<init>",
                Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Throwable.class)),
                false);
        // ->
        mw.visitInsn(Opcodes.ATHROW);
        mw.visitLabel(endTryCatchBlock);
        mw.visitInsn(Opcodes.RETURN);
        mw.visitMaxs(0, 0);
        mw.visitEnd();
    }

    private static void methodImplementation(ClassWriter cw, Method method, String klassFullName) {
        // ->
        var sw = new SignatureWriter();
        for (var paramType : method.getGenericParameterTypes()) {
            var psw = sw.visitParameterType();
            signature(psw, paramType);
        }
        var rsw = sw.visitReturnType();
        signature(rsw, method.getGenericReturnType());
        String signature = sw.toString();
        var mw = cw.visitMethod(Opcodes.ACC_PUBLIC,
                method.getName(),
                Type.getMethodDescriptor(method),
                signature,
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
        boolean needScope = method.getReturnType().isRecord() || method.getReturnType().isArray() || method.getReturnType() == Pointer.class;
        needScope = needScope || Arrays.stream(method.getParameterTypes()).anyMatch(c -> c.isArray() || c.isRecord() || c == Pointer.class);
        if (needScope) {
            scopeLocalVarIndex = firstLocalSlot;
            firstLocalSlot += 1; // slot for scope
        } else {
            scopeLocalVarIndex = -1;
        }
        for (var param : Arrays.stream(method.getParameters())
                .map(Parameter::getAnnotatedType)
                .filter((annotatedType -> {
                    var rawType = InternalUtils.rawType(annotatedType.getType());
                    return rawType == Pointer.class && !annotatedType.isAnnotationPresent(NoSideEffect.class);
                }))
                .map(InternalUtils::wrapPrimitive)
                .toList()) {
            firstLocalSlot += 1;
        }

        // ->
        mw.visitTryCatchBlock(userTryBlock, userCatchBlock, userCatchBlock, Type.getInternalName(Throwable.class));
        {
            // ->
            mw.visitLabel(userTryBlock);
            if (needScope) {
                // ->
                scopefulTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
            } else {
                // ->
                scopelessTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
            }
            mw.visitJumpInsn(Opcodes.GOTO, endBlock);
        }

        {
            // -> throwable
            mw.visitLabel(userCatchBlock);
            // -> throwable -> runExc
            mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(RuntimeException.class));
            // -> runExc -> throwable -> runExc
            mw.visitInsn(Opcodes.DUP_X1);
            // -> runExc -> runExc -> throwable
            mw.visitInsn(Opcodes.SWAP);
            // -> runExc
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

    private static void scopelessTryBlock(MethodVisitor mw, Method method, String klassFullName, int firstLocalSlot, final int scopeLocalVarIndex) {
        // ->
        // -> (ret)
        resourceTryBlock(mw, method, klassFullName, firstLocalSlot, scopeLocalVarIndex);
        // ->
        returnBlock(mw, method);
    }

    private static void scopefulTryBlock(MethodVisitor mw, Method method, String klassFullName, int firstLocalSlot, final int scopeLocalVarIndex) {
        // ->
        var userTryBlock = new Label();
        var resourceTryBlock = new Label();
        var resourceCatchBlock = new Label();
        var userCatchBlock = new Label();
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
            // -> (ret) -> scope
            mw.visitVarInsn(Opcodes.ALOAD, scopeLocalVarIndex);
            // -> (ret)
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(NativeScope.class),
                    "close",
                    Type.getMethodDescriptor(Type.getType(void.class)),
                    true);
            // ->
            returnBlock(mw, method);
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
        mw.visitLocalVariable("primaryExc",
                Type.getDescriptor(Throwable.class),
                null,
                resourceCatchBlock, userCatchBlock,
                scopeLocalVarIndex + 1);
    }

    private static void resourceFinallyBlock(MethodVisitor mw, final int scopeLocalVarIndex) {
        // ->
        var endSuppressTryCatch = new Label();
        var suppressTryBlock = new Label();
        var suppressCatchBlock = new Label();
        // ->
        mw.visitTryCatchBlock(suppressTryBlock, suppressCatchBlock, suppressCatchBlock, Type.getInternalName(Throwable.class));
        // try {
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
            mw.visitJumpInsn(Opcodes.GOTO, endSuppressTryCatch);
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
            mw.visitLabel(endSuppressTryCatch);
        }
        // ->
    }

    private static void resourceTryBlock(MethodVisitor mw, Method method, String klassFullName, int firstLocalSlot, final int scopeLocalVarIndex) {
        // ->
        // -> handle
        mw.visitFieldInsn(Opcodes.GETSTATIC,
                klassFullName,
                method.getName(),
                Type.getDescriptor(MethodHandle.class));
        for (int i = 0, slot = 1, savedArgIndex = scopeLocalVarIndex + 1; i < method.getParameterCount(); i++) {
            // -> ...
            var param = method.getParameters()[i];
            var paramRawType = param.getType();
            if (paramRawType == boolean.class || paramRawType == byte.class || paramRawType == short.class || paramRawType == char.class || paramRawType == int.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.ILOAD, slot);
                slot += 1;
            } else if (paramRawType == long.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.LLOAD, slot);
                slot += 2;
            } else if (paramRawType == float.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.FLOAD, slot);
                slot += 1;
            } else if (paramRawType == double.class) {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.DLOAD, slot);
                slot += 2;
            } else {
                // -> ...
                // -> ... -> arg_i
                mw.visitVarInsn(Opcodes.ALOAD, slot);
                slot += 1;
                if (paramRawType == NativeLong.class) {
                    // ... -> wrap
                    // ... -> larg_i
                    mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            Type.getInternalName(NativeLong.class),
                            "value",
                            Type.getMethodDescriptor(Type.getType(long.class)),
                            false);
                    if (CLinker.C_LONG.byteSize() == 4) {
                        // ... -> arg_i
                        mw.visitInsn(Opcodes.L2I);
                    }
                    // -> ... -> arg_i
                }
            }
            // -> ... -> arg_i
            // -> ... -> modArg_i
            ArgumentResolver.resolveArgument(mw, param.getAnnotatedType(), firstLocalSlot, scopeLocalVarIndex);
            if (paramRawType == Pointer.class && !param.isAnnotationPresent(NoSideEffect.class)) {
                // -> ... -> modArg_i -> modArg_i
                mw.visitInsn(Opcodes.DUP);
                // -> ... -> modArg_i
                mw.visitVarInsn(Opcodes.ASTORE, savedArgIndex);
                savedArgIndex++;
            }
        }
        // -> handle -> arg0 -> arg1 -> ...
        var retTypeWrapper = Type.getType(InternalUtils.wrapPrimitive(method.getAnnotatedReturnType()));
        var paramTypeWrapperList = Arrays.stream(method.getParameters())
                .map(param -> InternalUtils.wrapPrimitive(param.getAnnotatedType()))
                .map(Type::getType).toList().toArray(new Type[method.getParameterCount()]);
        // -> (modret)
        mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(MethodHandle.class),
                "invokeExact",
                Type.getMethodDescriptor(retTypeWrapper, paramTypeWrapperList),
                false);
        // -> (modret)
        for (int i = 0, slot = 1, savedArgIndex = scopeLocalVarIndex + 1; i < method.getParameterCount(); i++) {
            // -> (modret)
            var param = method.getParameters()[i];
            var paramRawType = param.getType();
            if (paramRawType == Pointer.class && !param.isAnnotationPresent(NoSideEffect.class)) {
                // -> (modRet)
                var pointedType = ((ParameterizedType) param.getParameterizedType()).getActualTypeArguments()[0];
                // -> (modret) -> arg_i
                mw.visitVarInsn(Opcodes.ALOAD, slot);
                // -> (modret) -> arg_i -> arg_i
                mw.visitInsn(Opcodes.DUP);
                // -> (modret) -> arg_i -> arg_i -> modarg_i
                mw.visitVarInsn(Opcodes.ALOAD, savedArgIndex);
                // -> (modret) -> arg_i -> modarg_i -> arg_i
                mw.visitInsn(Opcodes.SWAP);
                // -> (modret) -> arg_i -> retArg_i
                ResultResolver.resolveResult(mw, param.getAnnotatedType(), firstLocalSlot, true);
                // -> (modret) -> arg_i -> retContent (uncasted)
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(Pointer.class),
                        "get",
                        Type.getMethodDescriptor(Type.getType(Object.class)),
                        false);
                // -> (modret) -> arg_i -> retContent
                mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(InternalUtils.rawType(pointedType)));
                // -> (modret)
                mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(Pointer.class),
                        "set",
                        Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Object.class)),
                        false);
                savedArgIndex++;
            }
            if (paramRawType == double.class || paramRawType == long.class) {
                slot += 2;
            } else {
                slot += 1;
            }
        }
        // -> (modret)
        // -> (ret)
        ResultResolver.resolveResult(mw, method.getAnnotatedReturnType(), firstLocalSlot, false);
    }

    private static void returnBlock(MethodVisitor mw, Method method) {
        // -> (ret)
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
        } else if (retType == NativeLong.class) {
            if (CLinker.C_LONG.byteSize() == 4) {
                // -> ret
                // -> lret
                mw.visitInsn(Opcodes.I2L);
            }
            // -> lret
            // -> lret -> wrap
            mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(NativeLong.class));
            // -> wrap -> lret -> wrap
            mw.visitInsn(Opcodes.DUP_X2);
            // -> wrap -> wrap -> lret -> wrap
            mw.visitInsn(Opcodes.DUP_X2);
            // -> wrap -> wrap -> lret
            mw.visitInsn(Opcodes.POP);
            // -> wrap
            mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    Type.getInternalName(NativeLong.class),
                    "<init>",
                    Type.getMethodDescriptor(Type.getType(void.class), Type.getType(long.class)),
                    false);
            // ->
            mw.visitInsn(Opcodes.ARETURN);
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
            // -> ... -> klass
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(wrapper),
                    "TYPE",
                    Type.getDescriptor(Class.class));
        } else {
            // -> ...
            // -> ... -> klass
            mw.visitLdcInsn(Type.getType(type));
        }
    }

    private static void getTypeDesc(MethodVisitor mw, AnnotatedType annotatedType) {
        // -> ...
        var rawType = InternalUtils.rawType(annotatedType.getType());
        if (InternalUtils.isPrimitive(rawType)) {
            // -> ...
            // -> ... -> desc
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(CLinker.class),
                    InternalUtils.cDescriptorName(rawType),
                    Type.getDescriptor(ValueLayout.class));
        } else if (rawType.isArray()) {
            // -> ...
            var length = Optional.ofNullable(annotatedType.getAnnotation(Length.class))
                    .orElseThrow(() -> new IllegalArgumentException("Array passed by value must have length"))
                    .value();
            // -> ... -> llength
            mw.visitLdcInsn((long) length);
            // -> ... -> llength -> componentDesc
            getTypeDesc(mw, ((AnnotatedArrayType) annotatedType).getAnnotatedGenericComponentType());
            // -> ... -> desc
            mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MemoryLayout.class),
                    "ofSequence",
                    Type.getMethodDescriptor(Type.getType(SequenceLayout.class), Type.getType(long.class), Type.getType(MemoryLayout.class)),
                    true);
        } else if (rawType.isRecord()) {
            // -> ...
            // -> ... -> desc
            getRecordTypeDesc(mw, rawType);
        } else if (rawType == Pointer.class) {
            // -> ...
            // -> ... -> desc
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(CLinker.class),
                    "C_POINTER",
                    Type.getDescriptor(ValueLayout.class));
        } else if (rawType == String.class) {
            // -> ...
            // -> ... -> desc
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(CLinker.class),
                    "C_POINTER",
                    Type.getDescriptor(ValueLayout.class));
        } else if (MemorySegment.class.isAssignableFrom(rawType)) {
            // -> ...
            var layout = Optional.ofNullable(annotatedType.getAnnotation(Layout.class))
                    .orElseThrow(() -> new IllegalArgumentException("MemorySegment passed by value must have specified layout"))
                    .value();
            // -> ... -> desc
            getRecordTypeDesc(mw, layout);
        } else if (MemoryAddress.class.isAssignableFrom(rawType)) {
            // -> ...
            // -> ... -> desc
            mw.visitFieldInsn(Opcodes.GETSTATIC,
                    Type.getInternalName(CLinker.class),
                    "C_POINTER",
                    Type.getDescriptor(ValueLayout.class));
        } else {
            throw new AssertionError("Unexpected type " + annotatedType.getType());
        }
        // -> ... -> desc
    }

    private static void getRecordTypeDesc(MethodVisitor mw, Class<?> rawType) {
        // -> ...
        var components = rawType.getRecordComponents();
        long currentOffset = 0;
        int layoutElements = 0;
        for (var component : components) {
            var currentSizeAlign = InternalUtils.layoutSize(component.getAnnotatedType());
            if (currentOffset % currentSizeAlign.alignment() == 0) {
                layoutElements += 1;
            } else {
                layoutElements += 2;
            }
            currentOffset = InternalUtils.align(currentOffset, currentSizeAlign.alignment());
            currentOffset += currentSizeAlign.size();
        }
        // -> ...
        // -> ... -> componentDescListLength
        mw.visitLdcInsn(layoutElements);
        // -> ... -> componentDescList
        mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
        currentOffset = 0;
        for (int i = 0, j = 0; i < components.length; i++, j++) {
            // -> ... -> componentDescList
            var component = components[i];
            var currentSizeAlign = InternalUtils.layoutSize(component.getAnnotatedType());
            if (currentOffset % currentSizeAlign.alignment() != 0) {
                long padding = (currentOffset / currentSizeAlign.alignment() + 1) * currentSizeAlign.alignment() - currentOffset;
                // -> ... -> componentDescList -> componentDescList
                mw.visitInsn(Opcodes.DUP);
                // -> ... -> componentDescList -> componentDescList -> j
                mw.visitLdcInsn(j);
                // -> ... -> componentDescList -> componentDescList -> j -> lpaddingLength
                mw.visitLdcInsn(padding * 8);
                // -> ... -> componentDescList -> componentDescList -> j -> padding
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MemoryLayout.class),
                        "ofPaddingBits",
                        Type.getMethodDescriptor(Type.getType(MemoryLayout.class), Type.getType(long.class)),
                        true);
                // -> ... -> componentDescList
                mw.visitInsn(Opcodes.AASTORE);
                j++;
            }
            // -> ... -> componentDescList -> componentDescList
            mw.visitInsn(Opcodes.DUP);
            // -> ... -> componentDescList -> componentDescList -> j
            mw.visitLdcInsn(j);
            // -> ... -> componentDescList -> componentDescList -> j -> componentDesc
            getTypeDesc(mw, component.getAnnotatedType());
            // -> ... -> componentDescList
            mw.visitInsn(Opcodes.AASTORE);

            currentOffset = InternalUtils.align(currentOffset, currentSizeAlign.alignment());
            currentOffset += currentSizeAlign.size();
        }
        // -> ... -> componentDescList
        // -> ... -> desc
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(MemoryLayout.class),
                "ofStruct",
                Type.getMethodDescriptor(Type.getType(GroupLayout.class), Type.getType(MemoryLayout.class.arrayType())),
                true);
    }

    private static void signature(SignatureVisitor sw, java.lang.reflect.Type type) {
        var rawType = InternalUtils.rawType(type);
        if (rawType.isPrimitive()) {
            sw.visitBaseType(Type.getDescriptor(rawType).charAt(0));
        } else if (rawType.isArray()) {
            var psw = sw.visitArrayType();
            java.lang.reflect.Type componentType;
            if (type instanceof GenericArrayType g) {
                componentType = g.getGenericComponentType();
            } else if (type instanceof Class<?> c) {
                componentType = c.componentType();
            } else {
                throw new AssertionError("Unexpected type " + type.getTypeName() + ", " + type.getClass().getName());
            }
            signature(psw, componentType);
        } else {
            sw.visitClassType(Type.getInternalName(rawType));
            if (type instanceof ParameterizedType p) {
                for (var typeParam : p.getActualTypeArguments()) {
                    var psw = sw.visitTypeArgument('=');
                    signature(psw, typeParam);
                }
            }
            sw.visitEnd();
        }
    }
}
