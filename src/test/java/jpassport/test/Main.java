package jpassport.test;

import jpassport.PassportFactory;
import jpassport.test.validity.TestLink;

public class Main {
    private static final TestLink BINDING;

    static {
        try {
            BINDING = PassportFactory.link("foreign_link", TestLink.class);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {

    }
}
