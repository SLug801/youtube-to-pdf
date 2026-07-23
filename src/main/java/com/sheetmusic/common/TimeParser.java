package com.sheetmusic.common;

/**
 * 사용자 입력 시간(초, mm:ss, hh:mm:ss)을 영상 시작 기준 초 단위로 변환한다.
 */
public final class TimeParser {

    private TimeParser() {}

    public static double parseSeconds(String value) {
        if (value == null || value.isBlank()) return 0;

        String input = value.trim();
        String[] parts = input.split(":", -1);
        if (parts.length < 1 || parts.length > 3) throw invalid(value);

        try {
            if (parts.length == 1) {
                double seconds = Double.parseDouble(parts[0]);
                if (!Double.isFinite(seconds) || seconds < 0) throw invalid(value);
                return seconds;
            }

            long total = 0;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isBlank() || !parts[i].matches("\\d+")) throw invalid(value);
                long part = Long.parseLong(parts[i]);
                if (i > 0 && part >= 60) throw invalid(value);
                total = Math.addExact(Math.multiplyExact(total, 60), part);
            }
            return total;
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalid(value);
        }
    }

    public static String formatSeconds(double seconds) {
        long rounded = Math.max(0, Math.round(seconds));
        long hours = rounded / 3600;
        long minutes = (rounded % 3600) / 60;
        long secs = rounded % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%02d:%02d", minutes, secs);
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException(
                "시작 시각 형식이 올바르지 않습니다: " + value + " (예: 75, 01:15, 1:02:30)");
    }
}
