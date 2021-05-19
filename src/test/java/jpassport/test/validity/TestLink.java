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

import jdk.incubator.foreign.MemoryAddress;
import jpassport.Passport;
import jpassport.Pointer;

import java.lang.invoke.MethodHandles;

public interface TestLink extends Passport {
    static MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    double sumD(double d, double d2);
    double sumArrD(Pointer<double[]> d, int len);
    double sumArrDD(Pointer<double[]> d, Pointer<double[]> d2, int len);
    void readD(Pointer<double[]> d, int set);

    float sumArrF(Pointer<float[]> i, int len);
    void readF(Pointer<float[]> d, float set);

    long sumArrL(Pointer<long[]> i, long len);
    void readL(Pointer<long[]> d, long set);

    int sumArrI(Pointer<int[]> i, int len);
    void readI(Pointer<int[]> d, int set);

    short sumArrS(Pointer<short[]> i, short len);
    void readS(Pointer<short[]> d, short set);

    byte sumArrB(Pointer<byte[]> i, byte len);
    void readB(Pointer<byte[]> d, byte set);

    double sumMatDPtrPtr(int rows, int cols, Pointer<Pointer<double[]>[]> mat);
    float sumMatFPtrPtr(int rows, int cols, Pointer<Pointer<float[]>[]> mat);

    long sumMatLPtrPtr(int rows, int cols, Pointer<Pointer<long[]>[]> mat);
    int sumMatIPtrPtr(int rows, int cols, Pointer<Pointer<int[]>[]> mat);
    int sumMatSPtrPtr(int rows, int cols, Pointer<Pointer<short[]>[]> mat);
    int sumMatBPtrPtr(int rows, int cols, Pointer<Pointer<byte[]>[]> mat);

    MemoryAddress mallocDoubles(int count);
    void freeDoubleArray(MemoryAddress address);

    double passStruct(TestStruct address);
    double passComplexPtr(Pointer<ComplexStruct> complexStruct);
    double passComplex(ComplexStruct complexStruct);
}
