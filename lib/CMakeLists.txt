cmake_minimum_required(VERSION 3.16)
project(jfa CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ../../resources)

add_library(jfa_validity SHARED library.cpp)