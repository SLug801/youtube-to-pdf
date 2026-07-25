package com.sheetmusic.common;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();

    static CancellationToken none() {
        return () -> false;
    }
}
