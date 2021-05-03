package jpassport.impl;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jdk.incubator.foreign.*;
import jpassport.Passport;
import org.objectweb.asm.*;

public class ClassGenerator {
    private static int id = 0;

    public static <T extends Passport> T build(Class<T> interfaceKlass, Set<Class<?>> extraImports, String libraryName) throws IOException {
        int id = ClassGenerator.id++;
        var klassName = interfaceKlass.getSimpleName() + "_" + id;
        var fullName = interfaceKlass.getPackageName() + "." + klassName;
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V16,
                Opcodes.ACC_PUBLIC,
                klassName,
                null,
                "java/lang/Object",
                new String[] {interfaceKlass.getCanonicalName()});
        cw.visitModule(interfaceKlass.getModule().getName(), Opcodes.ACC_MANDATED, null);

        var methods  = Arrays.stream(interfaceKlass.getMethods())
                .filter(method -> {
                    int modifier = method.getModifiers();
                    return ((modifier & Modifier.PUBLIC) != 0) && ((modifier & Modifier.STATIC) == 0);
                }).toList();
        for (var method : methods) {
            var fw = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    method.getName(),
                    Type.getDescriptor(MethodHandle.class),
                    null,
                    null);
        }

        classInitializer(cw, fullName, methods, libraryName);

        for (var method : methods) {

        }
    }

    /**
     * Parse the class initializer, the resulted bytecode is similar to that compiled from this piece of source code
     *
     * <blockquote><pre>{@code
     * {
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
     * @param fullName The full name (internal name) of the class being written
     * @param methods The list of methods declared in the binding interface
     * @param libraryName The name of the native library needed to be loaded
     */
    private static void classInitializer(ClassWriter cw, String fullName, List<Method> methods, String libraryName){
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
        // libName
        mw.visitLdcInsn(libraryName);
        // libLookup
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(LibraryLookup.class),
                "ofLibrary",
                Type.getMethodDescriptor(Type.getType(LibraryLookup.class), Type.getType(String.class)),
                true);
        //
        mw.visitVarInsn(Opcodes.ASTORE, 0);
        // clinker
        mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(CLinker.class),
                "getInstance",
                Type.getMethodDescriptor(Type.getType(CLinker.class)),
                true);

        for (var method : methods) {
            // clinker -> clinker
            mw.visitInsn(Opcodes.DUP);
            // clinker -> clinker -> libLookup
            mw.visitVarInsn(Opcodes.ALOAD, 0);
            // clinker -> clinker -> symbolOptional
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(LibraryLookup.class),
                    "lookup",
                    Type.getMethodDescriptor(Type.getType(Optional.class), Type.getType(String.class)),
                    true);
            // clinker -> clinker -> symbol (uncasted)
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(Optional.class),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            // clinker -> clinker -> symbol
            mw.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(LibraryLookup.Symbol.class));
            var retType = InternalUtils.wrapPrimitive(method.getReturnType());
            var argTypeList = Arrays.stream(method.getParameterTypes())
                    .map(InternalUtils::wrapPrimitive).toList();
            // clinker -> clinker -> symbol -> retType
            mw.visitLdcInsn(retType);
            if (argTypeList.size() == 0) {
                // clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class)),
                        false);
            } else if (argTypeList.size() == 1) {
                // clinker -> clinker -> symbol -> retType -> argType_0
                mw.visitLdcInsn(argTypeList.get(0));
                // clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class), Type.getType(Class.class)),
                        false);
            } else {
                // clinker -> clinker -> symbol -> retType -> argListSize
                mw.visitLdcInsn(argTypeList.size());
                // clinker -> clinker -> symbol -> retType -> argList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Class.class));
                for (int i = 0; i < argTypeList.size(); i++) {
                    // clinker -> clinker -> symbol -> retType -> argList -> argList
                    mw.visitInsn(Opcodes.DUP);
                    // clinker -> clinker -> symbol -> retType -> argList -> argList -> i
                    mw.visitLdcInsn(i);
                    // clinker -> clinker -> symbol -> retType -> argList -> argList -> i -> argType_i
                    mw.visitLdcInsn(argTypeList.get(i));
                    // clinker -> clinker -> symbol -> retType -> argList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // clinker -> clinker -> symbol -> retType -> argList
                // clinker -> clinker -> symbol -> methodType
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(MethodType.class),
                        "methodType",
                        Type.getMethodDescriptor(Type.getType(MethodType.class), Type.getType(Class.class), Type.getType(Class.class.arrayType())),
                        false);
            }
            // clinker -> clinker -> symbol -> methodType
            if (retType == void.class) {
                // clinker -> clinker -> symbol -> methodType -> argDesSize
                mw.visitLdcInsn(argTypeList.size());
                // clinker -> clinker -> symbol -> methodType -> argDesList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
                for (int i = 0; i < argTypeList.size(); i++) {
                    // clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList
                    mw.visitInsn(Opcodes.DUP);
                    // clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList -> i
                    mw.visitLdcInsn(i);
                    // clinker -> clinker -> symbol -> methodType -> argDesList -> argDesList -> i -> argDes_i
                    mw.visitFieldInsn(Opcodes.GETSTATIC,
                            Type.getInternalName(CLinker.class),
                            InternalUtils.cDescriptorName(argTypeList.get(i)),
                            Type.getDescriptor(ValueLayout.class));
                    // clinker -> clinker -> symbol -> methodType -> argDesList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // clinker -> clinker -> symbol -> methodType -> argDesList
                // clinker -> clinker -> symbol -> methodType -> funcDes
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(FunctionDescriptor.class),
                        "ofVoid",
                        Type.getMethodDescriptor(Type.getType(FunctionDescriptor.class), Type.getType(MemoryLayout.class.arrayType())),
                        false);
            } else {
                // clinker -> clinker -> symbol -> methodType -> retDes
                mw.visitFieldInsn(Opcodes.GETSTATIC,
                        Type.getInternalName(CLinker.class),
                        InternalUtils.cDescriptorName(retType),
                        Type.getDescriptor(ValueLayout.class));
                // clinker -> clinker -> symbol -> methodType -> retDes -> argDesSize
                mw.visitLdcInsn(argTypeList.size());
                // clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                mw.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(MemoryLayout.class));
                for (int i = 0; i < argTypeList.size(); i++) {
                    // clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList
                    mw.visitInsn(Opcodes.DUP);
                    // clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList -> i
                    mw.visitLdcInsn(i);
                    // clinker -> clinker -> symbol -> methodType -> retDes -> argDesList -> argDesList -> i -> argDes_i
                    mw.visitFieldInsn(Opcodes.GETSTATIC,
                            Type.getInternalName(CLinker.class),
                            InternalUtils.cDescriptorName(argTypeList.get(i)),
                            Type.getDescriptor(ValueLayout.class));
                    // clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                    mw.visitInsn(Opcodes.AASTORE);
                }
                // clinker -> clinker -> symbol -> methodType -> retDes -> argDesList
                // clinker -> clinker -> symbol -> methodType -> funcDes
                mw.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(FunctionDescriptor.class),
                        "of",
                        Type.getMethodDescriptor(Type.getType(FunctionDescriptor.class), Type.getType(MemoryLayout.class), Type.getType(MemoryLayout.class.arrayType())),
                        false);
            }
            // clinker -> clinker -> symbol -> methodType -> funcDes
            // clinker -> methodHandle
            mw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(CLinker.class),
                    "downcallHandle",
                    Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.getType(Addressable.class), Type.getType(MethodType.class), Type.getType(FunctionDescriptor.class)),
                    true);
            // clinker
            mw.visitFieldInsn(Opcodes.PUTSTATIC,
                    fullName,
                    method.getName(),
                    Type.getDescriptor(MethodHandle.class));
        }

        // clinker
        //
        mw.visitInsn(Opcodes.POP);
        mw.visitJumpInsn(Opcodes.GOTO, finish);
        // causeExcep
        mw.visitLabel(catchBlock);
        //
        mw.visitVarInsn(Opcodes.ASTORE, 0);
        // runExcep
        mw.visitTypeInsn(Opcodes.NEW, Type.getInternalName(RuntimeException.class));
        // runExcep -> runExcep
        mw.visitInsn(Opcodes.DUP);
        // runExcep -> runExcep -> causeExcep
        mw.visitVarInsn(Opcodes.ALOAD, 0);
        // runExcep
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
                Type.getInternalName(RuntimeException.class),
                "<init>",
                Type.getMethodDescriptor(Type.getType(void.class), Type.getType(Throwable.class)),
                false);
        //
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

    private static void methodImplementation(ClassWriter cw, Method method) {
        var mw = cw.visitMethod(Opcodes.ACC_PUBLIC,
                method.getName(),
                Type.getMethodDescriptor(method),
                null,
                null);

    }

    private static void argumentTransformation(MethodVisitor mw, Class<?> argType) {
        // ... -> arg
        if (argType.isPrimitive()) {
            return;
        } else if (argType.isRecord()) {
            for (var componentType : )
        }
    }

    private static <T extends Record> void deconstructRecord(MethodVisitor mw, Class<T> argType) {
        // ... -> arg
        int layoutSize = InternalUtils.
        for (var componentType : argType.getRecordComponents()) {
            // ... -> arg -> arg
            mw.visitInsn(Opcodes.DUP);
            // ... -> arg -> component
            mw.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(argType),
                    componentType.getName(),
                    Type.getMethodDescriptor(componentType.getAccessor()),
                    false);
        }
    }
}
