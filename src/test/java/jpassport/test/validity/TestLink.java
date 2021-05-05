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
package jpassport.test.validity;

import com.sun.jna.Library;
import jdk.incubator.foreign.MemoryAddress;
import jpassport.Passport;
import jpassport.annotations.PtrPtrArg;
import jpassport.annotations.RefArg;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public interface TestLink extends Passport, Library {
    MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    double sumD(double d, double d2);
    double sumArrD(@RefArg double[] d, int len);
    double sumArrDD(@RefArg double[] d, @RefArg double[] d2, int len);
    void readD(@RefArg double[] d, int set);

    float sumArrF(@RefArg float[] i, int len);
    void readF(@RefArg float[] d, float set);

    long sumArrL(@RefArg long[] i, long len);
    void readL(@RefArg long[] d, long set);

    int sumArrI(@RefArg int[] i, int len);
    void readI(@RefArg int[] d, int set);

    short sumArrS(@RefArg short[] i, short len);
    void readS(@RefArg short[] d, short set);

    byte sumArrB(@RefArg byte[] i, byte len);
    void readB(@RefArg byte[] d, byte set);

    double sumMatDPtrPtr(int rows, int cols, @RefArg double[][] mat);
    float sumMatFPtrPtr(int rows, int cols, @RefArg float[][] mat);

    long sumMatLPtrPtr(int rows, int cols, @RefArg long[][] mat);
    int sumMatIPtrPtr(int rows, int cols, @RefArg int[][] mat);
    int sumMatSPtrPtr(int rows, int cols, @RefArg short[][] mat);
    int sumMatBPtrPtr(int rows, int cols, @RefArg byte[][] mat);

    MemoryAddress mallocDoubles(int count);
    void freeDoubleArray(MemoryAddress address);

    double passStruct(@RefArg TestStruct address);
    double passComplex(@RefArg ComplexStruct[] complexStruct);
}
