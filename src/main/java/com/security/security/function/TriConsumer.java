package com.security.security.function;

import java.util.Objects;

@FunctionalInterface
public interface TriConsumer<T, U , V> {
    void accept(T t , U u , V v);
//    default TriConsumer<T, U , V> andThen(TriConsumer<? super T> after) {
//        Objects.requireNonNull(after);
//        return (T t , U u , V v) -> { accept(t); after.accept(t); };
//    }
}
