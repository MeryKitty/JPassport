package jfa.impl;

import jdk.incubator.foreign.*;
import jfa.FunctionPointer;
import jfa.Pointer;
import jfa.annotations.Layout;
import jfa.annotations.Length;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.Optional;

public class FunctionPointerUtils {
    public static MemoryLayout descriptor(Type type, Optional<Length> arrayLength, Optional<Layout> segmentLayout) {
        var rawType = Utils.rawType(type);
        if (Utils.isPrimitive(type)) {
            return Utils.cDescription(type);
        } else if (rawType.isArray()) {
            Type componentType;
            if (type instanceof GenericArrayType g) {
                componentType = g.getGenericComponentType();
            } else if (type instanceof Class<?> c) {
                componentType = c.componentType();
            } else {
                throw new AssertionError("Unexpected type " + type + ", " + type.getClass());
            }
            return MemoryLayout.ofSequence(arrayLength.get().value(), descriptor(componentType, Optional.empty(), Optional.empty()));
        } else if (Utils.isStruct(type)) {
            var components = rawType.getRecordComponents();
            var structLayoutList = new MemoryLayout[components.length];
            for (int i = 0; i < components.length; i++) {
                var component = components[i];
                structLayoutList[i] = descriptor(component.getGenericType(), Optional.ofNullable(component.getAnnotation(Length.class)), Optional.empty());
            }
            return MemoryLayout.ofStruct(structLayoutList);
        } else if (rawType == Pointer.class || rawType == String.class || FunctionPointer.class.isAssignableFrom(rawType) || MemoryAddress.class.isAssignableFrom(rawType)) {
            return CLinker.C_POINTER;
        } else if (MemorySegment.class.isAssignableFrom(rawType)) {
            return descriptor(segmentLayout.get().value(), Optional.empty(), Optional.empty());
        } else {
            throw new AssertionError("Unexpected type " + type);
        }
    }
}
