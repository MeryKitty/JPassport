package jfa;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jfa.annotations.Layout;
import jfa.impl.FunctionPointerImpl;
import jfa.impl.FunctionPointerUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

public interface FunctionPointer {
    static FunctionPointer nativeFunction(MemoryAddress pointer, MethodType methodType, FunctionDescriptor funcDesc) {
        var handle = CLinker.getInstance().downcallHandle(pointer, methodType, funcDesc);
        return new FunctionPointerImpl(handle, pointer);
    }

    static FunctionPointer javaFunction(Method method) throws IllegalAccessException {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Must be a static method");
        }
        var handle = MethodHandles.lookup().unreflect(method);
        FunctionDescriptor desc;
        var returnType = method.getGenericReturnType();
        var paramList = method.getParameters();
        var paramDescList = new MemoryLayout[paramList.length];
        for (int i = 0; i < paramList.length; i++) {
            var param = paramList[i];
            paramDescList[i] = FunctionPointerUtils.descriptor(param.getParameterizedType(), Optional.empty(), Optional.ofNullable(param.getAnnotatedType().getAnnotation(Layout.class)));
        }
        if (returnType == void.class) {
            desc = FunctionDescriptor.ofVoid(paramDescList);
        } else {
            desc = FunctionDescriptor.of(FunctionPointerUtils.descriptor(returnType, Optional.empty(), Optional.ofNullable(method.getAnnotatedReturnType().getAnnotation(Layout.class))), paramDescList);
        }
        var pointer = CLinker.getInstance().upcallStub(handle, desc);
        return new FunctionPointerImpl(handle, pointer.address());
    }

    Object invoke(Object... arguments) throws Throwable;

    MethodHandle handle();

    MemoryAddress pointer();
}
