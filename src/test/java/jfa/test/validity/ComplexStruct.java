package jfa.test.validity;

import jfa.Pointer;
import jfa.annotations.Length;

public record ComplexStruct(Pointer<SimpleStruct> a, @Length(3) SimpleStruct[] b, String c) {
}
