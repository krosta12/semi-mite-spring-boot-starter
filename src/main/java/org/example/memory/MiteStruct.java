package org.example.memory;

import org.example.engine.NativeResource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class MiteStruct<T> implements NativeResource {
    private final Arena arena;
    private final MemorySegment segment;
    private final Class<T> type;

    public MiteStruct(Class<T> type) {
        this.type = type;
        this.arena = Arena.ofConfined();
        long size = StructLayoutCalculator.calculateSize(type);
        this.segment = arena.allocate(size);
    }

    public void write(T javaObject) {
        StructMarshaller.writeToSegment(javaObject, this.segment, this.arena);
    }

    public T read() {
        return StructMarshaller.readFromSegment(this.type, this.segment);
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