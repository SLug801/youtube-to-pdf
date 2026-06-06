package com.sheetmusic;

@FunctionalInterface
public interface ProgressLogger {
    void log(String message);

    static ProgressLogger console() {
        return System.out::println;
    }
}
