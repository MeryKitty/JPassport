package jfa.test.validity;

import jdk.incubator.foreign.MemoryAddress;
import jfa.NativeLong;

public record SimpleStruct(int a, NativeLong b, long c, MemoryAddress d) {}
