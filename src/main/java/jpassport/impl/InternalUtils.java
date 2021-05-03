package jpassport.impl;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ValueLayout;
import jpassport.annotations.ArrayValueArg;
import jpassport.annotations.RefArg;

import java.util.*;

public class InternalUtils {

    public static Class<?> wrapPrimitive(Class<?> type, boolean ref) {
        if (!isValidType(type)) {
            throw new IllegalArgumentException("Illegal type " + type.getSimpleName());
        }
        if (ref) {
            return MemoryAddress.class;
        } else {
            if (type.isPrimitive()) {
                return type;
            } else if (MemoryAddress.class == type) {
                return type;
            } else if (type.isRecord()) {
                return MemorySegment.class;
            } else if (type == String.class) {
                return MemoryAddress.class;
            } else if (type.isArray()) {
                return MemorySegment.class;
            } else {
                throw new AssertionError("Something's wrong, type " + type.getSimpleName());
            }
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
        // Types appear in a type trace can only be either MemoryAddress, MemorySegment, primitive types, or container
        // of valid types
        boolean valid = true;
        if (type.isPrimitive() || type == String.class || type == MemoryAddress.class) {
            valid = true;
        } else if (type.isArray()) {
            valid = isValidType(type.getComponentType());
        } else if (type.isRecord()) {
            for (var component : type.getRecordComponents()) {
                if (!isValidType(component.getType())) {
                    valid = false;
                    break;
                }
            }
        } else {
            valid = false;
        }
        if (!valid) {
            return false;
        }

        // Check struct dependency circularity, a type cannot depend on itself without indirection
        var checkedTypeList = new ArrayList<Class<?>>();
        var pendingTypeList = new ArrayList<Class<?>>();
        pendingTypeList.add(type);

        valid = true;
        while (!pendingTypeList.isEmpty()) {
            var beginTraverseType = pendingTypeList.remove(0);
            valid = traverseTypeTrace(beginTraverseType, checkedTypeList, pendingTypeList);
            if (!valid) {
                break;
            }
        }
        return valid;
    }

    private static boolean traverseTypeTrace(Class<?> beginType, List<Class<?>> checkedTypeList, List<Class<?>> pendingTypeList) {
        var encounteredTypeList = new ArrayList<Class<?>>();
        var traverseStack = new ArrayDeque<Class<?>>();
        boolean valid = traverseTypeTraceHelper(beginType, Collections.unmodifiableList(checkedTypeList), encounteredTypeList, traverseStack, pendingTypeList);
        if (valid) {
            checkedTypeList.addAll(encounteredTypeList);
        }
        return valid;
    }

    private static boolean traverseTypeTraceHelper(Class<?> currentType, List<Class<?>> checkedTypeList, List<Class<?>> encounteredType, Deque<Class<?>> traverseStack, List<Class<?>> pendingTypeList) {
        if (traverseStack.contains(currentType)) {
            return false;
        } else if (checkedTypeList.contains(currentType)) {
            return true;
        } else if (encounteredType.contains(currentType)) {
            return true;
        }
        boolean valid = true;
        traverseStack.push(currentType);
        encounteredType.add(currentType);
        if (currentType.isArray()) {
            valid = traverseTypeTraceHelper(currentType.componentType(), checkedTypeList, encounteredType, traverseStack, pendingTypeList);
        } else if (currentType.isRecord()) {
            for (var component : currentType.getRecordComponents()) {
                if (component.isAnnotationPresent(RefArg.class)) {
                    pendingTypeList.add(component.getType())
                } else {
                    if (!traverseTypeTraceHelper(component.getType(), checkedTypeList, encounteredType, traverseStack, pendingTypeList)) {
                        valid = false;
                        break;
                    }
                }
            }
        }
        traverseStack.pop();
        return valid;
    }

    public static String cDescriptorName(Class<?> type) {
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

    public static SizeData primitiveLayoutSize(Class<?> type) {
        var size = cDescription(type).byteSize();
        return new SizeData(size, size);
    }

    public static SizeData recordLayoutSize(Class<?> type) {
        long currentOffset = 0;
        long maxAlignment = 0;
        for (var component : type.getRecordComponents()) {
            var componentType = component.getType();
            if (component.isAnnotationPresent(RefArg.class)) {
                var temp = CLinker.C_POINTER.byteSize();
                currentOffset = align(currentOffset, temp) + temp;
                if (maxAlignment < temp) {
                    maxAlignment = temp;
                }
            } else {
                if (componentType.isRecord()) {
                    var componentSizeAndAlign = recordLayoutSize(componentType);
                    currentOffset = align(currentOffset, componentSizeAndAlign.alignment()) + componentSizeAndAlign.size();
                    if (maxAlignment < componentSizeAndAlign.alignment()) {
                        maxAlignment = componentSizeAndAlign.alignment();
                    }
                } else if (componentType.isArray()) {
                    var componentSizeAndAlign = arrayLayoutSize(componentType, Optional.ofNullable(component.getAnnotation(ArrayValueArg.class))
                            .map(ArrayValueArg::length)
                            .orElse(1));
                    currentOffset = align(currentOffset, componentSizeAndAlign.alignment()) + componentSizeAndAlign.size();
                    if (maxAlignment < componentSizeAndAlign.alignment()) {
                        maxAlignment = componentSizeAndAlign.alignment();
                    }
                } else {
                    var componentSizeAndAlign = primitiveLayoutSize(componentType);
                    currentOffset = align(currentOffset, componentSizeAndAlign.alignment()) + componentSizeAndAlign.size();
                    if (maxAlignment < componentSizeAndAlign.alignment()) {
                        maxAlignment = componentSizeAndAlign.alignment();
                    }
                }
            }
        }
        return new SizeData(currentOffset, maxAlignment);
    }

    public static SizeData arrayLayoutSize(Class<?> type, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException();
        }
        var component = type.componentType();
        long size;
        long align;
        if (component.isRecord()) {
            var componentSizeAndAlign = recordLayoutSize(component);
            long componentSizeAfterAlign = align(componentSizeAndAlign.size(), componentSizeAndAlign.alignment());
            size = componentSizeAfterAlign * (length - 1) + componentSizeAndAlign.size();
            align = componentSizeAndAlign.alignment();
        } else if (component.isArray()) {
            size = CLinker.C_POINTER.byteSize() * length;
            align = CLinker.C_POINTER.byteSize();
        } else {
            size = cDescription(component).byteSize() * length;
            align = cDescription(component).byteSize();
        }
        return new SizeData(size, align);
    }

    public static long align(long currentOffset, long alignment) {
        if (currentOffset % alignment != 0) {
            currentOffset = (currentOffset / alignment + 1) * alignment;
        }
        return currentOffset;
    }
}
