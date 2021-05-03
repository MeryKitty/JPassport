package jpassport.test;

import jpassport.test.performance.JPassportMicroBenchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class PerformanceTest {
    public static void main(String[] args) {
        Options opt = new OptionsBuilder()
                .include(JPassportMicroBenchmark.class.getSimpleName())
                .forks(1)
                .build();

        try {
            new Runner(opt).run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
