package jpassport.impl;

import java.lang.reflect.*;
import java.util.*;
import jdk.incubator.foreign.*;

import jpassport.NativeLong;
import jpassport.Pointer;
import jpassport.annotations.Layout;
import jpassport.annotations.Length;

public class InternalUtils {

    public static Class<?> wrapPrimitive(AnnotatedType annotatedType) {
        if (!isValidType(annotatedType)) {
            throw new IllegalArgumentException("Illegal type " + annotatedType);
        }
        var rawType = rawType(annotatedType.getType());
        if (isPrimitive(rawType)) {
            return rawType;
        } else if (rawType == NativeLong.class) {
            if (CLinker.C_LONG.byteSize() == 4) {
                return int.class;
            } else if (CLinker.C_LONG.byteSize() == 8) {
                return long.class;
            } else {
                throw new AssertionError("Unexpected native long size " + CLinker.C_LONG.byteSize());
            }
        } else if (rawType.isRecord() || rawType.isArray()) {
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
        var checkedTypeList = new ArrayList<AnnotatedType>();
        var pendingTypeList = new ArrayList<AnnotatedType>();
        pendingTypeList.add(annotatedType);
        var rawType = rawType(annotatedType.getType());
        if (rawType.isArray() && !annotatedType.isAnnotationPresent(Length.class)) {
            return false;
        } else if (MemorySegment.class.isAssignableFrom(rawType) && !annotatedType.isAnnotationPresent(Layout.class)) {
            return false;
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

    private static boolean traverseTypeTrace(AnnotatedType annotatedType, List<AnnotatedType> checkedAnnotatedTypeList, List<AnnotatedType> pendingAnnotatedTypeList) {
        var encounteredTypeList = new ArrayList<AnnotatedType>();
        var traverseStack = new ArrayDeque<AnnotatedType>();
        boolean valid = traverseTypeTraceHelper(annotatedType, Collections.unmodifiableList(checkedAnnotatedTypeList), encounteredTypeList, traverseStack, pendingAnnotatedTypeList);
        if (valid) {
            checkedAnnotatedTypeList.addAll(encounteredTypeList);
        }
        return valid;
    }

    private static boolean traverseTypeTraceHelper(AnnotatedType currentAnnotatedType, List<AnnotatedType> checkedAnnotatedTypeList, List<AnnotatedType> encounteredAnnotatedTypeList, Deque<AnnotatedType> traverseStack, List<AnnotatedType> pendingAnnotatedTypeList) {
        if (traverseStack.contains(currentAnnotatedType)) {
            return false;
        } else if (checkedAnnotatedTypeList.contains(currentAnnotatedType)) {
            return true;
        } else if (encounteredAnnotatedTypeList.contains(currentAnnotatedType)) {
            return true;
        }
        boolean valid = true;
        var rawType = rawType(currentAnnotatedType.getType());
        if (!(isPrimitive(rawType)
                || rawType.isArray()
                || rawType.isRecord()
                || rawType == Pointer.class
                || rawType == String.class
                || MemorySegment.class.isAssignableFrom(rawType)
                || MemoryAddress.class.isAssignableFrom(rawType))) {
            return false;
        }

        traverseStack.push(currentAnnotatedType);
        encounteredAnnotatedTypeList.add(currentAnnotatedType);
        if (rawType.isArray()) {
            if (!currentAnnotatedType.isAnnotationPresent(Length.class)) {
                valid = traverseStack.size() == 1;
            }
            if (currentAnnotatedType instanceof AnnotatedArrayType a) {
                valid = valid && traverseTypeTraceHelper(a.getAnnotatedGenericComponentType(), checkedAnnotatedTypeList, encounteredAnnotatedTypeList, traverseStack, pendingAnnotatedTypeList);
            } else {
                throw new AssertionError();
            }
        } else if (rawType.isRecord()) {
            for (var component : rawType.getRecordComponents()) {
                if (!traverseTypeTraceHelper(component.getAnnotatedType(), checkedAnnotatedTypeList, encounteredAnnotatedTypeList, traverseStack, pendingAnnotatedTypeList)) {
                    valid = false;
                    break;
                }
            }
        } else if (rawType == Pointer.class) {
            if (currentAnnotatedType instanceof AnnotatedParameterizedType p) {
                valid = p.getAnnotatedActualTypeArguments().length == 1;
                pendingAnnotatedTypeList.add(p.getAnnotatedActualTypeArguments()[0]);
            }
        } else if (MemorySegment.class.isAssignableFrom(rawType)) {
            valid = traverseStack.size() == 1 && !checkedAnnotatedTypeList.isEmpty() && currentAnnotatedType.isAnnotationPresent(Layout.class) && currentAnnotatedType.getAnnotation(Layout.class).value().isRecord();
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
        if (InternalUtils.isPrimitive(componentType)) {
            size = cDescription(componentRawType).byteSize() * length;
            align = cDescription(componentRawType).byteSize();
        } else if (componentRawType.isArray()) {
            throw new AssertionError("Can't reach here");
        } else if (componentRawType.isRecord()) {
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
            var componentSizeAndAlign = layoutSize(component.getAnnotatedType());
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

    public static SizeData layoutSize(AnnotatedType annotatedType) {
        var type = annotatedType.getType();
        var rawType = rawType(type);
        SizeData currentSizeAlign;
        if (InternalUtils.isPrimitive(type)) {
            currentSizeAlign = InternalUtils.primitiveLayoutSize(type);
        } else if (rawType.isArray()) {
            int length = annotatedType.getAnnotation(Length.class).value();
            currentSizeAlign = InternalUtils.arrayLayoutSize(type, length);
        } else if (rawType.isRecord()) {
            currentSizeAlign = InternalUtils.recordLayoutSize(type);
        } else if (rawType == Pointer.class) {
            currentSizeAlign = new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
        } else if (rawType == String.class) {
            currentSizeAlign = new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
        } else if (MemorySegment.class.isAssignableFrom(rawType)) {
            currentSizeAlign = InternalUtils.recordLayoutSize(annotatedType.getAnnotation(Layout.class).value());
        } else if (MemoryAddress.class.isAssignableFrom(rawType)) {
            currentSizeAlign = new SizeData(CLinker.C_POINTER.byteSize(), CLinker.C_POINTER.byteSize());
        } else {
            throw new AssertionError("Unexpected type " + type);
        }
        return currentSizeAlign;
    }
}
