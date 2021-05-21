module jfa.test {
    requires jdk.incubator.foreign;
    requires jdk.unsupported;

    requires jfa;
    requires com.sun.jna;
    requires com.sun.jna.platform;
    requires jmh.core;
    requires junit;
    requires commons.csv;

    exports jfa.test.validity;
}