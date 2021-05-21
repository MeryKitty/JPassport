package jfa.test.validity;

import jfa.annotations.Length;

public record DoubleArray10(@Length(10) double[] value) {
}
