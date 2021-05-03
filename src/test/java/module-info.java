module jpassport.test {
    requires jdk.incubator.foreign;
    requires jdk.unsupported;

    requires jpassport;
    requires com.sun.jna;
    requires com.sun.jna.platform;
    requires jmh.core;
    requires junit;
    requires commons.csv;

    exports jpassport.test;
    exports jpassport.test.performance;
    exports jpassport.test.validity;
    exports jpassport.test.util;
}