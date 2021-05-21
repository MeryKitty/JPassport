package jfa.test.validity;

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jfa.Linker;
import jfa.NativeLong;
import jfa.Pointer;
import org.junit.Test;
import static org.junit.Assert.*;

import java.lang.invoke.MethodHandles;
import java.util.Random;

public class ValidityTest {
    static final double EPSILON = 1e-9;
    static final int BOUND = 10000;
    static final Binding LIB;
    static final Random RANDOM;

    static {
        try {
            RANDOM = new Random();
            LIB = Linker.linkLibrary("jfa_validity", Binding.class, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void simple() {
        int a1 = RANDOM.nextInt(BOUND);
        long a2 = RANDOM.nextInt(BOUND);
        assertEquals((double)a1 + a2, LIB.simple(a1, a2), EPSILON);
    }

    @Test
    public void nativeLong() {
        NativeLong a1 = new NativeLong(RANDOM.nextInt(BOUND));
        int a2 = RANDOM.nextInt(BOUND);
        assertEquals(a1.value() + a2, LIB.nativeLong(a1, a2).value());
    }

    @Test
    public void allocateStruct() {
        var result = LIB.allocateStruct();
        assertEquals(result.get().a(), 1);
        assertEquals(result.get().b().value(), 2);
        assertEquals(result.get().c(), 3);
        assertEquals(MemoryAccess.getInt(result.get().d().asSegmentRestricted(4)), 4);
    }

    @Test
    public void passAndReturnStruct() {
        int a = RANDOM.nextInt(BOUND);
        NativeLong b = new NativeLong(RANDOM.nextInt(BOUND));
        long c = RANDOM.nextInt(BOUND);
        MemorySegment dSeg = MemorySegment.allocateNative(4);
        int d = RANDOM.nextInt();
        MemoryAccess.setInt(dSeg, d);
        var pass = new SimpleStruct(a, b, c, dSeg.address());
        int inc = RANDOM.nextInt(BOUND);
        pass = LIB.passAndReturnStruct(pass, inc);
        assertEquals(pass.a(), a + inc);
        assertEquals(pass.b().value(), b.value() + inc);
        assertEquals(pass.c(), c + inc);
        assertEquals(MemoryAccess.getInt(dSeg), d + inc);
    }

    @Test
    public void passPointerToStruct() {
        int a = RANDOM.nextInt(BOUND);
        NativeLong b = new NativeLong(RANDOM.nextInt(BOUND));
        long c = RANDOM.nextInt(BOUND);
        MemorySegment dSeg = MemorySegment.allocateNative(4);
        int d = RANDOM.nextInt();
        MemoryAccess.setInt(dSeg, d);
        var pass = new Pointer<>(new SimpleStruct(a, b, c, dSeg.address()));
        int inc = RANDOM.nextInt(BOUND);
        LIB.passPointerToStruct(pass, inc);
        assertEquals(pass.get().a(), a + inc);
        assertEquals(pass.get().b().value(), b.value() + inc);
        assertEquals(pass.get().c(), c + inc);
        assertEquals(MemoryAccess.getIntAtOffset(dSeg, pass.get().d().segmentOffset(dSeg)), d + inc);
    }

    @Test
    public void getArray() {
        double lower = RANDOM.nextDouble();
        double step = RANDOM.nextDouble();
        double[] expected = new double[10];
        double val = lower;
        for (int i = 0; i < 10; i++, val += step) {
            expected[i] = val;
        }
        assertArrayEquals(expected, LIB.getArray(lower, step).value(), EPSILON);
    }

    @Test
    public void createComplexStruct() {
        int a1a = RANDOM.nextInt(BOUND);
        var a1b = new NativeLong(RANDOM.nextInt(BOUND));
        long a1c = RANDOM.nextInt(BOUND);
        var a1dSeg = MemorySegment.allocateNative(4);
        int a1d = RANDOM.nextInt(BOUND);
        MemoryAccess.setInt(a1dSeg, a1d);
        var a1 = new Pointer<>(new SimpleStruct(a1a, a1b, a1c, a1dSeg.address()));
        int a2a = RANDOM.nextInt(BOUND);
        var a2b = new NativeLong(RANDOM.nextInt(BOUND));
        long a2c = RANDOM.nextInt(BOUND);
        var a2dSeg = MemorySegment.allocateNative(4);
        int a2d = RANDOM.nextInt(BOUND);
        MemoryAccess.setInt(a2dSeg, a2d);
        var a2 = new Pointer<>(new SimpleStruct(a2a, a2b, a2c, a2dSeg.address()));
        String message = "hello";
        var result = LIB.createComplexStruct(a1, a2, message);
        assertEquals(a1.get(), result.a().get());
        assertEquals(a2.get(), result.b()[0]);
        assertEquals(a1.get(), result.b()[1]);
        assertEquals(new SimpleStruct(0, new NativeLong(0), 0, MemoryAddress.ofLong(0)), result.b()[2]);
        assertEquals(result.c(), message);
    }

    @Test
    public void incrementNoSideEffect() {
        int length = RANDOM.nextInt(BOUND);
        double[] test = new double[length];
        for (int i = 0; i < length; i++) {
            test[i] = RANDOM.nextDouble();
        }
        var arg = new Pointer<>(test);
        LIB.incrementNoSideEffect(arg, length);
        assertArrayEquals(test, arg.get(), EPSILON);
    }
}
