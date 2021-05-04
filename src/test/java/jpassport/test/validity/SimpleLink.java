package jpassport.test.validity;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jpassport.Passport;

import java.lang.invoke.MethodType;

public interface SimpleLink extends Passport {
    static final MethodType methodType = MethodType.methodType(double.class, double.class);
    static final FunctionDescriptor funcDesc = FunctionDescriptor.of(CLinker.C_DOUBLE, CLinker.C_DOUBLE);

    double simple(double d);
}
