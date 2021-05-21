/* Copyright (c) 2021 Mai Dang Quan Anh, All Rights Reserved
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
package jfa;

import jfa.impl.ClassGenerator;

import java.lang.invoke.MethodHandles;

public class Linker {
    /**
     * Generate a binding for an interface with a library with given name
     *
     * @param libraryName    The library name (filename without extension on all platforms, without lib prefix on Linux and Mac)
     * @param interfaceClass The binding interface.
     * @return A class linked to call into a DLL or SO using the Foreign Linker.
     */
    public static <T> T linkLibrary(String libraryName, Class<T> interfaceClass, MethodHandles.Lookup lookup) throws IllegalAccessException {
        return ClassGenerator.build(interfaceClass, lookup, libraryName);
    }



    public static <T> T linkLibrary(String libraryName, Class<T> interfaceClass) throws IllegalAccessException {
        return ClassGenerator.build(interfaceClass, MethodHandles.lookup(), libraryName);
    }
}
