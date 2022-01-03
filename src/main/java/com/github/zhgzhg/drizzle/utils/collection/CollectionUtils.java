package com.github.zhgzhg.drizzle.utils.collection;

import java.util.Collection;
import java.util.Map;

public final class CollectionUtils {
    private CollectionUtils() {
        throw new UnsupportedOperationException("Not intended for instantiation");
    }

    public static boolean isNullOrEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    public static boolean isNullOrEmpty(Map<?, ?> m) {
        return m == null || m.isEmpty();
    }
}
