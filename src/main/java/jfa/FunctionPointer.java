package jfa;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jfa.impl.FunctionPointerImpl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

public interface FunctionPointer {
    static FunctionPointer nativeFunction(MemoryAddress pointer, MethodType methodType, FunctionDescriptor funcDesc) {
        var handle = CLinker.getInstance().downcallHandle(pointer, methodType, funcDesc);
        return new FunctionPointerImpl(handle, pointer);
    }

    Object invoke(Object... arguments) throws Throwable;

    MethodHandle handle();

    MemoryAddress pointer();
}
