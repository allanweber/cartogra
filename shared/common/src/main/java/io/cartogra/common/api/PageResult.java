package io.cartogra.common.api;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(
        List<T> items,
        long total,
        int limit,
        int offset
) {
    public static <T> PageResult<T> of(List<T> items, long total, int limit, int offset) {
        return new PageResult<>(items, total, limit, offset);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total, limit, offset);
    }
}
