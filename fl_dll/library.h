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
#ifndef FL_DLL_LIBRARY_H
#define FL_DLL_LIBRARY_H

extern "C" {
double sumD(double d1, double d2);
double sumArrD(const double *arr, int count);
double sumArrDD(const double *arr, const double *arr2, int count);
void readD(double *v, int set);
double sumMatD(int rows, int cols, const double* mat);
double sumMatDPtrPtr(int rows, int cols, const double** mat);

float sumArrF(const float *arr, int count);
void readF(float *val, float set);
float sumMatF(int rows, int cols, const float* mat);
float sumMatFPtrPtr(int rows, int cols, const float** mat);

long long sumArrL(const long long *arr, long long count);
void readL(long long *val, long long set);
long long sumMatL(int rows, int cols, const long long* mat);
long long sumMatLPtrPtr(int rows, int cols, const long long** mat);

int sumArrI(const int *arr, int count);
void readI(int *val, int set);
int sumMatI(int rows, int cols, const int* mat);
int sumMatIPtrPtr(int rows, int cols, const int** mat);

short sumArrS(const short *arr, short count);
void readS(short *val, short set);
int sumMatS(int rows, int cols, const short* mat);
int sumMatSPtrPtr(int rows, int cols, const short ** mat);

char sumArrB(const char *arr, char count);
void readB(char *val, char set);
int sumMatB(int rows, int cols, const char* mat);
int sumMatBPtrPtr(int rows, int cols, const char ** mat);

int cstringLength(const char* string);
char* mallocString(const char* origString);
double* mallocDoubles(int count);
void freeDoubleArray(double *memory);
}

struct PassingData
{
    int s_int;
    long long s_long;
    float s_float;
    double s_double;
};

struct ComplexPassing
{
    int s_ID;
    struct PassingData s_passingData;
    struct PassingData* s_ptrPassingData;
    char* s_string;
};
#endif //FL_DLL_LIBRARY_H
