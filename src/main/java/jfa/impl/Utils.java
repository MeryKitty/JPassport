package jfa.impl;

import java.lang.reflect.*;
import java.util.*;
import jdk.incubator.foreign.*;

import jfa.NativeLong;
import jfa.Pointer;
import jfa.annotations.Layout;
import jfa.annotations.Length;

public class Utils {

    public static Class<?> wrapPrimitive(AnnotatedType annotatedType) {
        if (!isValidType(annotatedType)) {
            throw new IllegalArgumentException("Illegal type " + annotatedType);
        }
        var type = annotatedType.getType();
        var rawType = rawType(type);
        if (isPrimitive(type)) {
            if (rawType == NativeLong.class) {
                if (CLinker.C_LONG.byteSize() == 4) {
                    return int.class;
                } else {
                    return long.class;
                }
            } else {
                return rawType;
            }
        } else if (isStruct(type) || rawType.isArray()) {
            return MemorySegment.class;
        } else if (rawType == Pointer.class || rawType == String.class) {
            return MemoryAddress.class;
        } else if (MemorySegment.class.isAssignableFrom(rawType)) {
            return MemorySegment.class;
        } else if (MemoryAddress.class.isAssignableFrom(rawType)) {
            return MemoryAddress.class;
        } else {
            throw new AssertionError("Something's wrong, type " + annotatedType);
        }
    }

    public static boolean isValidType(AnnotatedType annotatedType) {
        // Check struct dependency, including circularity
        var checkedTypeList = new ArrayList<Type>();
        var pendingTypeList = new ArrayList<Type>();
        pendingTypeList.add(annotatedType.getType());
        var rawType = rawType(annotatedType.getType());
        if (rawType.isArray()) {
            return false;
        } else if (MemorySegment.class.isAssignableFrom(rawType)) {
            return annotatedType.isAnnotationPresent(Layout.class);
        }

        boolean valid = true;
        while (!pendingTypeList.isEmpty()) {
            var beginTraverseType = pendingTypeList.remove(0);
            valid = traverseTypeTrace(beginTraverseType, checkedTypeList, pendingTypeList);
            if (!valid) {
                break;
            }
        }
        return valid;
    }

    private static boolean traverseTypeTrace(Type type, List<Type> checkedTypeList, List<Type> pendingTypeList) {
        var encounteredTypeList = new ArrayList<Type>();
        var traverseStack = new ArrayDeque<Type>();
        boolean valid = traverseTypeTraceHelper(type, Collections.unmodifiableList(checkedTypeList), encounteredTypeList, traverseStack, pendingTypeList);
        if (valid) {
            checkedTypeList.addAll(encounteredTypeList);
        }
        return valid;
    }

    private static boolean traverseTypeTraceHelper(Type currentType, List<Type> checkedTypeList, List<Type> encounteredTypeList, Deque<Type> traverseStack, List<Type> pendingTypeList) {
        if (traverseStack.contains(currentType)) {
            return false;
        } else if (checkedTypeList.contains(currentType)) {
            return true;
        } else if (encounteredTypeList.contains(currentType)) {
            return true;
        }
        boolean valid = true;
        var rawType = rawType(currentType);
        if (!(isPrimitive(currentType)
                || rawType.isArray()
                || isStruct(currentType)
                || rawType == Pointer.class
                || rawType == String.class
                || MemoryAddress.class.isAssignableFrom(rawType))) {
            return false;
        }
        traverseStack.push(currentType);
        encounteredTypeList.add(currentType);
        if (rawType.isArray()) {
            Type componentType;
            if (currentType instanceof GenericArrayType g) {
                componentType = g.getGenericComponentType();
            } else if (currentType instanceof Class<?> c) {
                componentType = c.componentType();
            } else {
                throw new AssertionError("Unexpected type " + currentType + ", " + currentType.getClass());
            }
            valid = traverseTypeTraceHelper(componentType, checkedTypeList, encounteredTypeList, traverseStack, pendingTypeList);
        } else if (isStruct(currentType)) {
            for (var component : rawType.getRecordComponents()) {
                if (component.getType().isArray() && !component.isAnnotationPresent(Length.class)) {
                    valid = false;
                    break;
                }
                if (!traverseTypeTraceHelper(component.getGenericType(), checkedTypeList, encounteredTypeList, traverseStack, pendingTypeList)) {
                    valid = false;
                    break;
                }
            }
        } else if (rawType == Pointer.class) {
            var p = (ParameterizedType) currentType;
            pendingTypeList.add(p.getActualTypeArguments()[0]);
        }
        traverseStack.pop();
        return valid;
    }

    public static Class<?> rawType(Type type) {
        Class<?> klass;
        {
            if (type instanceof Class<?> c) {
                klass = c;
            } else if (type instanceof ParameterizedType p) {
                klass = (Class<?>) p.getRawType();
            } else if (type instanceof GenericArrayType g) {
                klass = rawType(g.getGenericComponentType()).arrayType();
            } else {
                throw new AssertionError("Unexpected type " + type.getTypeName() + ", " + type.getClass().getName());
            }
        }
        return klass;
    }

    public static String cDescriptorName(Type type) {
        String result;
        if (type == boolean.class) {
            result = "C_CHAR";
        } else if (type == byte.class) {
            result = "C_CHAR";
        } else if (type == short.class) {
            result = "C_SHORT";
        } else if (type == char.class) {
            result = "C_SHORT";
        } else if (type == int.class) {
            result = "C_INT";
        } else if (type == NativeLong.class) {
            result = "C_LONG";
        } else if (type == long.class) {
            result = "C_LONG_LONG";
        } else if (type == float.class) {
            result = "C_FLOAT";
        } else if (type == double.class) {
            result = "C_DOUBLE";
        } else {
            throw new AssertionError("Unexpected error, type " + type.getTypeName());
        }
        return result;
    }

    public static ValueLayout cDescription(Type type) {
        ValueLayout result;
        if (type == boolean.class) {
            result = CLinker.C_CHAR;
        } else if (type == byte.class) {
            result = CLinker.C_CHAR;
        } else if (type == short.class) {
            result = CLinker.C_SHORT;
        } else if (type == char.class) {
            result = CLinker.C_SHORT;
        } else if (type == int.class) {
            result = CLinker.C_INT;
        } else if (type == NativeLong.class) {
            result = CLinker.C_LONG;
        } else if (type == long.class) {
            result = CLinker.C_LONG_LONG;
        } else if (type == float.class) {
            result = CLinker.C_FLOAT;
        } else if (type == double.class) {
            result = CLinker.C_DOUBLE;
        } else {
            throw new AssertionError("Unexpected type " + type.getTypeName());
        }
        return result;
    }

    public static SizeData primitiveLayoutSize(Type type) {
        var size = cDescription(type).byteSize();
        return new SizeData(size, size);
    }

    public static SizeData arrayLayoutSize(Type type, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException();
        }
        Type componentType;
        if (type instanceof GenericArrayType g) {
            componentType = g.getGenericComponentType();
        } else if (type instanceof Class<?> c) {
            componentType = c.componentType();
        } else {
            throw new AssertionError("Unexpected type " + type.getTypeName() + ", " + type.getClass().getName());
        }
        var componentRawType = rawType(componentType);
        long size;
        long align;
        if (Utils.isPrimitive(componentType)) {
            size = cDescription(componentRawType).byteSize() * length;
            align = cDescription(componentRawType).byteSize();
        } else if (componentRawType.isArray()) {
            throw new AssertionError("Can't reach here");
        } else if (isStruct(componentType)) {
            var componentSizeAndAlign = recordLayoutSize(componentRawType);
            long componentSizeAfterAlign = align(componentSizeAndAlign.size(), componentSizeAndAlign.alignment());
            size = componentSizeAfterAlign * (length - 1) + componentSizeAndAlign.size();
            align = componentSizeAndAlign.alignment();
        } else if (componentRawType == Pointer.class) {
            size = CLinker.C_POINTER.byteSize();
            align = size;
        } else if (componentRawType == String.class) {
            size = CLinker.C_POINTER.byteSize();
            align = size;
        } else if (MemorySegment.class.isAssignableFrom(componentRawType)) {
            throw new AssertionError("Can't reach here");
        } else if (MemoryAddress.class.isAssignableFrom(componentRawType)) {
            size = CLinker.C_POINTER.byteSize();
            align = size;
        } else {
            throw new AssertionError("Unexpected type " + componentType);
        }
        return new SizeData(size, align);
    }

    public static SizeData recordLayoutSize(Type type) {
        var rawType = rawType(type);
        long currentOffset = 0;
        long maxAlignment = 0;
        for (var component : rawType.getRecordComponents()) {
            var componentSizeAndAlign = layoutSize(component.getGenericType(), Optional.ofNullable(component.getAnnotation(Length.class)));
            if (maxAlignment < componentSizeAndAlign.alignment()) {
                maxAlignment = componentSizeAndAlign.alignment();
            }
            currentOffset = align(currentOffset, componentSizeAndAlign.alignment());
            currentOffset += componentSizeAndAlign.size();
        }
        return new SizeData(currentOffset, maxAlignment);
    }

    public static SizeData pointerLayoutSize() {
        return new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
    }

    public static long align(long currentOffset, long alignment) {
        if (currentOffset % alignment != 0) {
            currentOffset = (currentOffset / alignment + 1) * alignment;
        }
        return currentOffset;
    }

    public static boolean isPrimitive(Type type) {
        var rawType = rawType(type);
        return rawType.isPrimitive() || rawType == NativeLong.class;
    }

    public static boolean isStruct(Type type) {
        var rawType = rawType(type);
        return rawType.isRecord() && rawType != NativeLong.class;
    }

    public static SizeData layoutSize(Type type, Optional<Length> arrayLength) {
        var rawType = rawType(type);
        SizeData currentSizeAlign;
        if (Utils.isPrimitive(type)) {
            currentSizeAlign = Utils.primitiveLayoutSize(type);
        } else if (rawType.isArray()) {
            int length = arrayLength.get().value();
            currentSizeAlign = Utils.arrayLayoutSize(type, length);
        } else if (isStruct(type)) {
            currentSizeAlign = Utils.recordLayoutSize(type);
        } else if (rawType == Pointer.class) {
            currentSizeAlign = new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
        } else if (rawType == String.class) {
            currentSizeAlign = new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
        } else if (MemoryAddress.class.isAssignableFrom(rawType)) {
            currentSizeAlign = new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
        } else {
            throw new AssertionError("Unexpected type " + type);
        }
        return currentSizeAlign;
    }
}
