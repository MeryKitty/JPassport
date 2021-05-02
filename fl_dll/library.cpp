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

#include <cstdio>
#include <cstdlib>
#include <cstring>

static_assert(sizeof(char) == 1);
static_assert(sizeof(short) == 2);
static_assert(sizeof(int) == 4);
static_assert(sizeof(long long) == 8);

extern "C" {
double sumD(const double d1, const double d2)
{
    return (d1 + d2);
}

double sumArrD(const double *arr, const int count)
{
    double r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n];

    return r;
}

double sumArrDD(const double *arr,const double *arr2, const int count)
{
    double r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n] + arr2[n];

    return r;
}

void readD(double *val, int set)
{
    *val = (double)set;
}

float sumArrF(const float *arr, const int count)
{
    float r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n];

    return r;
}

void readF(float *val, float set)
{
    *val = set;
}

long long sumArrL(const long long *arr, const long long count)
{
    long long r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n];

    return r;
}

void readL(long long *val, long long set)
{
    *val = set;
}

int sumArrI(const int *arr, const int count)
{
    int r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n];

    return r;
}

void readI(int *val, int set)
{
    *val = set;
}

short sumArrS(const short *arr, const short count)
{
    short r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n];

    return r;
}

void readS(short *val, short set)
{
    *val = set;
}

char sumArrB(const char *arr, const char count)
{
    char r = 0;

    for (int n = 0; n < count; ++n)
        r += arr[n];

    return r;
}

void readB(char *val, char set)
{
    *val = set;
}


double sumMatD(int rows, int cols, const double* mat)
{
    int total = 0;

    for (auto i = 0; i < rows * cols; i++) {
        total += mat[i];
    }

    return total;
}

double sumMatDPtrPtr(const int rows, const int cols, const double** mat)
{
    double total = 0;

    for (int yy = 0; yy < rows; ++yy)
    {
        for (int xx = 0; xx < cols; ++xx)
            total += mat[yy][xx];
    }
    return total;
}


float sumMatF(int rows, int cols, const float* mat)
{
    float total = 0;

    for (auto i = 0; i < rows * cols; i++) {
        total += mat[i];
    }

    return total;
}

float sumMatFPtrPtr(const int rows, const int cols, const float** mat)
{
    float total = 0;

    for (int yy = 0; yy < rows; ++yy)
    {
        for (int xx = 0; xx < cols; ++xx)
            total += mat[yy][xx];
    }
    return total;
}

long long sumMatL(int rows, int cols, const long long* mat)
{
    long long total = 0;

    for (auto i = 0; i < rows * cols; i++) {
        total += mat[i];
    }

    return total;
}

long long sumMatLPtrPtr(const int rows, const int cols, const long long** mat)
{
    long long total = 0;

    for (int yy = 0; yy < rows; ++yy)
    {
        for (int xx = 0; xx < cols; ++xx)
            total += mat[yy][xx];
    }
    return total;
}

int sumMatI(int rows, int cols, const int* mat)
{
    int total = 0;

    for (auto i = 0; i < rows * cols; i++) {
        total += mat[i];
    }

    return total;
}

int sumMatIPtrPtr(const int rows, const int cols, const int** mat)
{
    int total = 0;

    for (int yy = 0; yy < rows; ++yy)
    {
        for (int xx = 0; xx < cols; ++xx)
            total += mat[yy][xx];
    }
    return total;
}

int sumMatS(int rows, int cols, const short* mat)
{
    int total = 0;

    for (auto i = 0; i < rows * cols; i++) {
        total += mat[i];
    }

    return total;
}

int sumMatSPtrPtr(const int rows, const int cols, const short ** mat)
{
    int total = 0;

    for (int yy = 0; yy < rows; ++yy)
    {
        for (int xx = 0; xx < cols; ++xx)
            total += mat[yy][xx];
    }
    return total;
}


int sumMatB(int rows, int cols, const char* mat)
{
    int total = 0;

    for (auto i = 0; i < rows * cols; i++) {
        total += mat[i];
    }

    return total;
}

int sumMatBPtrPtr(const int rows, const int cols, const char ** mat)
{
    int total = 0;

    for (int yy = 0; yy < rows; ++yy)
    {
        for (int xx = 0; xx < cols; ++xx)
            total += mat[yy][xx];
    }
    return total;
}

int cstringLength(const char* string)
{
    return strlen(string);
}

char* mallocString(const char* origString)
{
    char* ret = new char[strlen(origString)];
    strcpy(ret, origString);
    return ret;
}

double* mallocDoubles(const int count)
{
    double* ret = new double[count];

    for (int n = 0; n < count; ++n)
        ret[n] = (double)n;

    return ret;
}

double passStruct(struct PassingData* data)
{
    double ret = 0;
    ret += (double)data->s_long;
    ret += data->s_float;
    ret += data->s_int;
    ret += data->s_double;

    return ret;
}

double passComplex(struct ComplexPassing* complex)
{
    double ret = passStruct(&complex->s_passingData);
    ret += passStruct(complex->s_ptrPassingData);

    int len = strlen(complex->s_string);
    for (int n = 0; n < len; ++n)
        complex->s_string[n] -= 32;

    complex->s_ID += 10;
    complex->s_passingData.s_int += 10;
    complex->s_ptrPassingData->s_int +=20;
    return ret;
}
}