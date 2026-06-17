package org.example.config;

public class MiteContext {
    private static volatile int alignmentBytes = 4;

    public static void init(int bytes) {
        alignmentBytes = bytes;
    }

    public static int getAlignmentBytes() {
        return alignmentBytes;
    }
}