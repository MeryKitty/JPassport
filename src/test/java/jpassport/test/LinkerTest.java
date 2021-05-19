/* Copyright (c) 2021 Duncan McLean, All Rights Reserved
 *
 * The contents of this file is dual-licensed under the
 * Apache License 2.0.
 *
 * You may obtain a copy of the Apache License at:
 *
 * http://www.apache.org/licenses/
 *
 * A copy is also included in the downloadable source code.
 */
package jpassport.test;

import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jpassport.PassportFactory;
import jpassport.Pointer;
import jpassport.Utils;
import jpassport.test.validity.ComplexStruct;
import jpassport.test.validity.TestLink;
import jpassport.test.validity.TestStruct;
import org.junit.Test;

import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;

@SuppressWarnings("unchecked")
public class LinkerTest
{
    static final double EPSILON = 1E-5;

    static TestLink test;

    static {
        try {
            test = PassportFactory.link("foreign_link", TestLink.class, TestLink.LOOKUP);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testD()
    {
        assertEquals(4 + 5, test.sumD(4, 5), EPSILON);
        assertEquals(1+2+3, test.sumArrD(new Pointer<>(new double[] {1, 2, 3}), 3), EPSILON);
        assertEquals(1+2+3+4+5+6, test.sumArrDD(new Pointer<>(new double[] {1, 2, 3}), new Pointer<>(new double[] {4, 5, 6}), 3), EPSILON);
        double[] v = new double[1];
        test.readD(new Pointer<>(v), 5);
        assertEquals(5, v[0], EPSILON);
    }

    @Test
    public void testF()
    {
            assertEquals(1+2+3, test.sumArrF(new Pointer<>(new float[] {1, 2, 3}), 3), EPSILON);

            float[] v = new float[1];
            test.readF(new Pointer<>(v), 5);
            assertEquals(5, v[0], EPSILON);
    }


    @Test
    public void testL()
    {
            assertEquals(1+2+3, test.sumArrL(new Pointer<>(new long[] {1, 2, 3}), 3));

            long[] v = new long[1];
            test.readL(new Pointer<>(v), 5);
            assertEquals(5, v[0]);
    }


    @Test
    public void testI()
    {
        int[] testRange = IntStream.range(1, 5).toArray();
        int correct = IntStream.range(1, 5).sum();

            assertEquals(correct, test.sumArrI(new Pointer<>(testRange), testRange.length));

            int[] v = new int[1];
            test.readI(new Pointer<>(v), 5);
            assertEquals(5, v[0]);
    }


    @Test
    public void testS()
    {
            assertEquals(1+2+3, test.sumArrS(new Pointer<>(new short[] {1, 2, 3}), (short)3));

            short[] v = new short[1];
            test.readS(new Pointer<>(v), (short)5);
            assertEquals(5, v[0]);
    }


    @Test
    public void testB()
    {
            assertEquals(1+2+3, test.sumArrB(new Pointer<>(new byte[] {1, 2, 3}), (byte)3));

            byte[] v = new byte[1];
            test.readB(new Pointer<>(v), (byte)5);
            assertEquals(5, v[0]);
    }

    @Test
    public void testSumMatD()
    {
        var mat0 = new Pointer<>(new double[]{1, 2, 3});
        var mat1 = new Pointer<>(new double[]{4, 5, 6});
        var mat2 = new Pointer<>(new double[]{7, 8, 9});
        var mat3 = new Pointer<>(new double[]{10, 11, 12});
        var mat = (Pointer<double[]>[]) new Pointer[] {mat0, mat1, mat2, mat3};
        int correct = IntStream.range(1,13).sum();
            assertEquals(correct, test.sumMatDPtrPtr(mat.length, mat[0].get().length, new Pointer<>(mat)), EPSILON);
    }

    @Test
    public void testSumMatF()
    {
        var mat = (Pointer<float[]>[]) new Pointer[] {new Pointer<>(new float[] {1,2,3}),
                new Pointer<>(new float[] {4,5,6}),
                new Pointer<>(new float[] {7,8,9}),
                new Pointer<>(new float[] {10,11,12})};
        int correct = IntStream.range(1,13).sum();
            assertEquals(correct, test.sumMatFPtrPtr(mat.length, mat[0].get().length, new Pointer<>(mat)), EPSILON);
    }

    @Test
    public void testSumMatL()
    {
        var mat = (Pointer<long[]>[]) new Pointer[] {new Pointer<>(new long[] {1,2,3}),
                new Pointer<>(new long[] {4,5,6}),
                new Pointer<>(new long[] {7,8,9}),
                new Pointer<>(new long[] {10,11,12})};
        int correct = IntStream.range(1,13).sum();
            assertEquals(correct, test.sumMatLPtrPtr(mat.length, mat[0].get().length, new Pointer<>(mat)));
    }

    @Test
    public void testSumMatI()
    {
        var mat = (Pointer<int[]>[]) new Pointer[] {new Pointer<>(new int[] {1,2,3}),
                new Pointer<>(new int[] {4,5,6}),
                new Pointer<>(new int[] {7,8,9}),
                new Pointer<>(new int[] {10,11,12})};
        int correct = IntStream.range(1,13).sum();
            assertEquals(correct, test.sumMatIPtrPtr(mat.length, mat[0].get().length, new Pointer<>(mat)));
    }

    @Test
    public void testSumMatS()
    {
        var mat = (Pointer<short[]>[]) new Pointer[] {new Pointer<>(new short[] {1,2,3}),
                new Pointer<>(new short[] {4,5,6}),
                new Pointer<>(new short[] {7,8,9}),
                new Pointer<>(new short[] {10,11,12})};
        int correct = IntStream.range(1,13).sum();
            assertEquals(correct, test.sumMatSPtrPtr(mat.length, mat[0].get().length, new Pointer<>(mat)));
    }

    @Test
    public void testSumMatB()
    {
        var mat = (Pointer<byte[]>[]) new Pointer[] {new Pointer<>(new byte[] {1,2,3}),
                new Pointer<>(new byte[] {4,5,6}),
                new Pointer<>(new byte[] {7,8,9}),
                new Pointer<>(new byte[] {10,11,12})};
        int correct = IntStream.range(1,13).sum();
            assertEquals(correct, test.sumMatBPtrPtr(mat.length, mat[0].get().length, new Pointer<>(mat)));
    }

    @Test
    public void testReturnPointer()
    {
        double[] values = new double[5];
        MemoryAddress address = test.mallocDoubles(values.length);
        MemorySegment segment = address.asSegmentRestricted(values.length * Double.BYTES);
        Utils.toArr(values, segment);

        var expected = new double[] {0, 1, 2, 3, 4};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], values[i], EPSILON);
        }

        test.freeDoubleArray(address);
    }


    @Test
    public void testSimpleStruct()
    {
        assertEquals(2+3+4+5, test.passStruct(new TestStruct(2, 3, 4, 5)), EPSILON);
    }

    @Test
    public void testComplexStructPtr()
    {
        TestStruct ts = new TestStruct(1, 2, 3, 4);
        TestStruct tsPtr = new TestStruct(5, 6, 7, 8);
        var complex = new Pointer<>(new ComplexStruct(55, ts, new Pointer<>(tsPtr), "hello"));

        double d = test.passComplexPtr(complex);
        assertEquals(IntStream.range(1, 9).sum(), d, EPSILON);
        assertEquals(65, complex.get().ID());
        assertEquals(11, complex.get().ts().s_int());
        assertEquals(25, complex.get().tsPtr().get().s_int());
        assertEquals("HELLO", complex.get().string());
    }

    @Test
    public void testComplexStruct()
    {
        TestStruct ts = new TestStruct(1, 2, 3, 4);
        TestStruct tsPtr = new TestStruct(5, 6, 7, 8);
        var complex = new ComplexStruct(55, ts, new Pointer<>(tsPtr), "hello");

        double d = test.passComplex(complex);
        assertEquals(IntStream.range(1, 9).sum(), d, EPSILON);
    }
}
