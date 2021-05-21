package jfa.test.validity;

import jdk.incubator.foreign.MemoryAddress;
import jfa.NativeLong;
import jfa.Pointer;
import jfa.annotations.Length;
import jfa.annotations.NoSideEffect;

public interface Binding {
    double simple(int a1, long a2);
    NativeLong nativeLong(NativeLong a1, int a2);
    Pointer<SimpleStruct> allocateStruct();
    SimpleStruct passAndReturnStruct(SimpleStruct a1, int inc);
    void passPointerToStruct(Pointer<SimpleStruct> a, int inc);
    DoubleArray10 getArray(double lower, double step);
    ComplexStruct createComplexStruct(Pointer<SimpleStruct> a1, Pointer<SimpleStruct> a2, String message);
    void incrementNoSideEffect(@NoSideEffect Pointer<double[]> arg, int len);
}
