package jfa.impl;

import jdk.incubator.foreign.MemoryAddress;
import jfa.FunctionPointer;

import java.lang.invoke.MethodHandle;

public record FunctionPointerImpl(MethodHandle handle, MemoryAddress pointer) implements FunctionPointer {
    @Override
    public Object invoke(Object... arguments) throws Throwable {
        return handle.invokeWithArguments(arguments);
    }
}
