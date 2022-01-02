package com.github.zhgzhg.drizzle.utils.misc;

public class MutableBoolean {
    private boolean value;

    public MutableBoolean() {
        this.value = false;
    }

    public MutableBoolean(boolean value) {
        this.value = value;
    }

    public void set(boolean value) {
        this.value = value;
    }

    public boolean get() {
        return this.value;
    }
}
