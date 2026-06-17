package org.example.engine;

import java.lang.foreign.MemorySegment;

public interface NativeResource extends AutoCloseable {
    MemorySegment segment();
}