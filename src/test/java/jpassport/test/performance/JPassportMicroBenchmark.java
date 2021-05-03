package jpassport.test.performance;

import com.sun.jna.Native;
import jpassport.PassportFactory;
import jpassport.test.validity.PureJava;
import jpassport.test.validity.TestLink;
import jpassport.test.validity.TestLinkJNADirect;
import org.openjdk.jmh.annotations.*;


import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class JPassportMicroBenchmark
{
    static TestLink testFL;
    static TestLink testJNA;
    static TestLink testJNADirect;
    static TestLink testJava;

    @Param({"1024", "2048", "16384", "262144"})
    public int array_size;

    public double[] test_arr;

    @Setup(Level.Trial)
    public void updateArray()
    {
        test_arr = IntStream.range(0, array_size).mapToDouble(i -> i).toArray();

    }

    @Setup()
    public void setUp() throws Throwable
    {
        testFL = PassportFactory.link("foreign_link", TestLink.class);
        testJNA =  Native.load("foreign_link", TestLink.class);
        testJNADirect =  new TestLinkJNADirect.JNADirect();
        testJava = new PureJava();
    }


    @Benchmark
    @Fork(value = 2, warmups = 1)
    public void sumTestArrDJava()
    {
        testJava.sumArrD(test_arr, test_arr.length);
    }

    @Benchmark
    @Fork(value = 2, warmups = 1)
    public void sumTestArrDJNA()
    {
        testJNA.sumArrD(test_arr, test_arr.length);
    }

    @Benchmark
    @Fork(value = 2, warmups = 1)
    public void sumTestArrDJNADirect()
    {
        testJNADirect.sumArrD(test_arr, test_arr.length);
    }

    @Benchmark
    @Fork(value = 2, warmups = 1)
    public void sumTestArrDJPassport()
    {
        testFL.sumArrD(test_arr, test_arr.length);
    }
}
