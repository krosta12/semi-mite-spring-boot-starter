package org.example.memory;

import org.example.engine.NativeResource;
import org.example.config.MiteContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;

public class MiteArray implements NativeResource, AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int length;

    public MiteArray(int length, MemoryLayout elementLayout) {
        this.length = length;
        this.arena = Arena.ofConfined();
        long byteSize = (long) length * elementLayout.byteSize();

        int currentAlignment = MiteContext.getAlignmentBytes();

        this.segment = arena.allocate(byteSize, currentAlignment);
    }


    public static MiteArray ofFloats(int length) {
        return new MiteArray(length, ValueLayout.JAVA_FLOAT);
    }

    public static MiteArray ofInts(int length) {
        return new MiteArray(length, ValueLayout.JAVA_INT);
    }

    public static MiteArray ofDoubles(int length) {
        return new MiteArray(length, ValueLayout.JAVA_DOUBLE);
    }


    public static MiteArray ofStrings(String... strings) {
        MiteArray array = new MiteArray(strings.length, ValueLayout.ADDRESS);
        for (int i = 0; i < strings.length; i++) {
            MemorySegment cString = array.arena.allocateFrom(strings[i]);
            array.segment.setAtIndex(ValueLayout.ADDRESS, i, cString);
        }
        return array;
    }


    public float getFloat(int index) {
        return segment.getAtIndex(ValueLayout.JAVA_FLOAT, index);
    }

    public void setFloat(int index, float value) {
        segment.setAtIndex(ValueLayout.JAVA_FLOAT, index, value);
    }

    public int getInt(int index) {
        return segment.getAtIndex(ValueLayout.JAVA_INT, index);
    }

    public void setInt(int index, int value) {
        segment.setAtIndex(ValueLayout.JAVA_INT, index, value);
    }

    public double getDouble(int index) {
        return segment.getAtIndex(ValueLayout.JAVA_DOUBLE, index);
    }

    public void setDouble(int index, double value) {
        segment.setAtIndex(ValueLayout.JAVA_DOUBLE, index, value);
    }

    public int length() {
        return length;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public void close() {
        arena.close();
    }
}