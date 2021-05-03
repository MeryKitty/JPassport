module jpassport {
    requires jdk.incubator.foreign;
    requires jdk.compiler;
    requires org.objectweb.asm;

    exports jpassport;
    exports jpassport.annotations;
}