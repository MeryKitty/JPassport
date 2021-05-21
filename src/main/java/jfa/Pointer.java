package jfa;

import java.util.Objects;

public final class Pointer<T> {
    private T value;

    public Pointer(T value) {
        this.set(value);
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = Objects.requireNonNull(value);
    }
}