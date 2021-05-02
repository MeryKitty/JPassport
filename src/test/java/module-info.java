module test.passport {
    requires jdk.incubator.foreign;
    requires jdk.unsupported;

    requires jpassport;
    requires com.sun.jna;
    requires com.sun.jna.platform;
    requires org.junit.jupiter.api;
    requires org.junit.platform.engine;
    requires jmh.core;
    requires commons.csv;

    exports jpassport.test;
    exports jpassport.test.performance;
}