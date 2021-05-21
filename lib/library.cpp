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
#include "library.h"

static_assert(sizeof(char) == 1);
static_assert(sizeof(short) == 2);
static_assert(sizeof(int) == 4);
static_assert(sizeof(long long) == 8);

extern "C" {
double simple(int a1, long long a2) {
    return a1 + a2;
}

long nativeLong(long a1, int a2) {
    return a1 + a2;
}

SimpleStruct* allocateStruct() {
    auto a = new SimpleStruct();
    a->a = 1;
    a->b = 2;
    a->c = 3;
    a->d = new int(4);
    return a;
}

SimpleStruct passAndReturnStruct(SimpleStruct a, int inc) {
    a.a += inc;
    a.b += inc;
    a.c += inc;
    *(a.d) += inc;
    return a;
}
void passPointerToStruct(SimpleStruct* a, int inc) {
    a->a += inc;
    a->b += inc;
    a->c += inc;
    *(a->d) += inc;
}

std::array<double, 10> getArray(double lower, double step) {
    std::array<double, 10> a;
    double val = lower;
    for (auto i = 0; i < 10; i++, val += step) {
        a[i] = val;
    }
    return a;
}

ComplexStruct createComplexStruct(SimpleStruct* a1, SimpleStruct* a2, char* message) {
    ComplexStruct result;
    result.a = a1;
    result.b[0] = *a2;
    result.b[1] = *a1;
    result.message = message;
    return result;
}

void incrementNoSideEffect(double* arg, int len) {
    for (auto i = 0; i < len; i++) {
        arg[i]++;
    }
}

}