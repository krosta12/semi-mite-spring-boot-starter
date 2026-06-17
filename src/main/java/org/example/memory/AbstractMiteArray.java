package org.example.memory;

import org.example.engine.NativeResource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.example.config.MiteContext;

public abstract class AbstractMiteArray implements NativeResource, AutoCloseable {
    protected final Arena arena;
    protected final MemorySegment segment;
    protected final int length;

    protected AbstractMiteArray(int length, long elementByteSize) {
        this.length = length;
        this.arena = Arena.ofConfined();
        long totalBytes = length * elementByteSize;
        this.segment = arena.allocate(totalBytes, MiteContext.getAlignmentBytes());
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