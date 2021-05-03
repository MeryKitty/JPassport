package jpassport.impl;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.ValueLayout;

public class InternalUtils {
    public static Class<?> wrapPrimitive(Class<?> type) {
        if (!isValidType(type)) {
            throw new IllegalArgumentException("Illegal type " + type.getSimpleName());
        }
        if (type.isPrimitive()) {
            return type;
        } else if (MemoryAddress.class == type) {
            return type;
        } else if (type.isRecord()) {
            return MemoryAddress.class;
        } else if (type == String.class) {
            return MemoryAddress.class;
        } else if (type.isArray()) {
            return MemoryAddress.class;
        } else {
            throw new AssertionError("Something's wrong, type " + type.getSimpleName());
        }
    }

    public static Pair<Integer, Class<?>> resolveArray(Class<?> type) {
        int level = 0;
        while (type.isArray()) {
            level++;
            type = type.getComponentType();
        }
        return new Pair<>(level, type);
    }

    public static boolean isValidType(Class<?> type) {
        if (type.isPrimitive() || type == String.class || type == MemoryAddress.class) {
            return true;
        }
        if (type.isArray()) {
            return isValidType(type.getComponentType());
        }
        if (type.isRecord()) {
            boolean valid = true;
            for (var component : type.getRecordComponents()) {
                if (!isValidType(component.getType())) {
                    valid = false;
                    break;
                }
            }
            return valid;
        }
        return false;
    }

    public static String cDescriptorName(Class<?> type) {
        String result;
        if (type == byte.class) {
            result = "C_CHAR";
        } else if (type == short.class) {
            result = "C_SHORT";
        } else if (type == char.class) {
            result = "C_SHORT";
        } else if (type == int.class) {
            result = "C_INT";
        } else if (type == long.class) {
            result = "C_LONG_LONG";
        } else if (type == float.class) {
            result = "C_FLOAT";
        } else if (type == double.class) {
            result = "C_DOUBLE";
        } else if (type == MemoryAddress.class) {
            result = "C_POINTER";
        } else {
            throw new AssertionError("Unexpected error, type " + type.getSimpleName());
        }
        return result;
    }

    public static ValueLayout cDescription(Class<?> type) {
        ValueLayout result;
        if (type == byte.class) {
            result = CLinker.C_CHAR;
        } else if (type == short.class) {
            result = CLinker.C_SHORT;
        } else if (type == char.class) {
            result = CLinker.C_SHORT;
        } else if (type == int.class) {
            result = CLinker.C_INT;
        } else if (type == long.class) {
            result = CLinker.C_LONG_LONG;
        } else if (type == float.class) {
            result = CLinker.C_FLOAT;
        } else if (type == double.class) {
            result = CLinker.C_DOUBLE;
        } else if (type == MemoryAddress.class) {
            result = CLinker.C_POINTER;
        } else {
            throw new AssertionError("Unexpected type " + type.getSimpleName());
        }
        return result;
    }

    public static Pair<Long, Long> recordLayoutSize(Class<?> type) {
        long currentOffset = 0;
        long maxComponentSize = 0;
        for (var component : type.getRecordComponents()) {
            var componentType = component.getType();
            if (Record.class.isAssignableFrom(componentType)) {
                var componentSizeAndAlign = recordLayoutSize(componentType);

            }
            var desc = cDescription(wrapPrimitive(componentType));
            var size = desc.byteSize();
            if (currentOffset % size != 0) {
                currentOffset = (currentOffset / size + 1) * size;
            }
            currentOffset += size;
            if (maxComponentSize < size) {
                maxComponentSize = size;
            }
        }
        return new Pair<>(currentOffset, maxComponentSize);
    }

    public static long arrayLayoutSize(Class<?> type, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException();
        }
        var component = type.componentType();
        long size;
        if (Record.class.isAssignableFrom(component)) {
            var componentSizeAndAlign = recordLayoutSize(component);
            long componentSizeAfterAlign = align(componentSizeAndAlign.first(), componentSizeAndAlign.second());
            size = componentSizeAfterAlign * length;
        } else if (component.isArray()) {
            size = CLinker.C_POINTER.byteSize() * length;
        } else {
            size = cDescription(component).byteSize() * length;
        }
        return size;
    }

    public static long align(long currentOffset, long alignment) {
        if (currentOffset % alignment != 0) {
            currentOffset = (currentOffset / alignment + 1) * alignment;
        }
        return currentOffset;
    }
}
