package com.sheetmusic;

@FunctionalInterface
public interface ProgressLogger {
    void log(String message);

    /** 기본 콘솔 출력 로거 반환 */
    static ProgressLogger console() {
        return message -> System.out.println(message);
    }
}