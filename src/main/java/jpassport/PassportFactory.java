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
package jpassport;

import jpassport.impl.ClassGenerator;

import java.lang.invoke.MethodHandles;

public class PassportFactory {
    /**
     * Call this method to generate the library linkage.
     *
     * @param libraryName    The library name (filename without extension on all platforms, without lib prefix on Linux and Mac,
     *                       or the full name of the file)
     * @param interfaceClass The class to wrap.
     * @param <T>
     * @return A class linked to call into a DLL or SO using the Foreign Linker.
     */
    public static <T extends Passport> T link(String libraryName, Class<T> interfaceClass, MethodHandles.Lookup lookup) throws IllegalAccessException {
        if (!Passport.class.isAssignableFrom(interfaceClass)) {
            throw new IllegalArgumentException("Interface (" + interfaceClass.getSimpleName() + ") of library=" + libraryName + " does not extend " + Passport.class.getSimpleName());
        } else {
            return ClassGenerator.build(interfaceClass, lookup, libraryName);
        }
    }

    public static <T extends Passport> T link(String libraryName, Class<T> interfaceClass) throws IllegalAccessException {
        if (!Passport.class.isAssignableFrom(interfaceClass)) {
            throw new IllegalArgumentException("Interface (" + interfaceClass.getSimpleName() + ") of library=" + libraryName + " does not extend " + Passport.class.getSimpleName());
        } else {
            return ClassGenerator.build(interfaceClass, MethodHandles.lookup(), libraryName);
        }
    }
}
