package jpassport;

public final class NativeLong {
    private final long value;

    public NativeLong(long value) {
        this.value = value;
    }

    public long value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof NativeLong l) {
            return value == l.value;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return (int)value;
    }
}
