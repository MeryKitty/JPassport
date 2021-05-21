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
#ifndef JFA_VALIDITY_LIBRARY_H
#define JFA_VALIDITY_LIBRARY_H

#include <array>

struct SimpleStruct
{
    int a;
    long b;
    long long c;
    int* d;
};

struct ComplexStruct
{
    SimpleStruct* a;
    std::array<SimpleStruct, 3> b;
    char* message;
};

extern "C" {
double simple(int a1, long long a2);
long nativeLong(long a1, int a2);
SimpleStruct* allocateStruct();
SimpleStruct passAndReturnStruct(SimpleStruct a, int inc);
void passPointerToStruct(SimpleStruct* a, int inc);
std::array<double, 10> getArray(double lower, double step);
ComplexStruct createComplexStruct(SimpleStruct* a1, SimpleStruct* a2, char* message);
void incrementNoSideEffect(double* arg, int len);
}

#endif //JFA_VALIDITY_LIBRARY_H
