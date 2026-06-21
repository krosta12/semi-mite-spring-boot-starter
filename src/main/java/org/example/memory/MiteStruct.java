package org.example.memory;

import org.example.engine.NativeResource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A typed off-heap container for a single flat Java struct, enabling explicit
 * low-level control over how an object is written to and read from native memory.
 *
 * <p>{@code MiteStruct<T>} allocates a native memory segment whose size is
 * calculated by {@link StructLayoutCalculator} to match the C++ struct layout
 * for {@code T}. Data is transferred via {@link StructMarshaller}:
 * {@link #write} serializes a Java object into the segment, and {@link #read}
 * deserializes the current segment content back into a new Java instance.
 *
 * <p><b>Limitations:</b> {@code MiteStruct} uses {@link StructMarshaller}, which
 * supports only flat primitive fields and {@code String}. Nested objects,
 * collections, arrays, and cyclic references are not supported. For those cases,
 * pass the object directly to {@link org.example.engine.CppEngine#execute} —
 * {@link org.example.engine.DefaultCppEngine} handles complex graphs automatically.
 *
 * <p>Example usage:
 * <pre>{@code
 * @MiteStruct
 * public class Point {
 *     public float x;
 *     public float y;
 * }
 *
 * try (MiteStruct<Point> nativePoint = new MiteStruct<>(Point.class)) {
 *     nativePoint.write(new Point(1.0f, 2.0f));
 *     engine.execute("transform_point", nativePoint);
 *     Point result = nativePoint.read();
 * }
 * }</pre>
 *
 * <p>This class is not thread-safe. The underlying confined {@link Arena} may
 * only be accessed from the thread that created it.
 *
 * @param <T> the Java type whose layout this struct represents;
 *            must have a public no-arg constructor for {@link #read()} to work
 * @see StructMarshaller
 * @see StructLayoutCalculator
 * @see org.example.annotation.MiteStruct
 */
public class MiteStruct<T> implements NativeResource {
    private final Arena arena;
    private final MemorySegment segment;
    private final Class<T> type;

    /**
     * Allocates off-heap memory for a single instance of {@code type}.
     *
     * <p>The segment size is computed by {@link StructLayoutCalculator#calculateSize}
     * using the same natural-alignment rules applied by {@link StructMarshaller},
     * ensuring that field offsets match between Java and C++.
     *
     * @param type the class whose C++ struct layout this container represents;
     *             must have a public no-arg constructor for {@link #read()} to work
     */
    public MiteStruct(Class<T> type) {
        this.type = type;
        this.arena = Arena.ofConfined();
        long size = StructLayoutCalculator.calculateSize(type);
        this.segment = arena.allocate(size);
    }

    /**
     * Serializes the given Java object into the native memory segment.
     *
     * <p>Field values are written using {@link StructMarshaller#writeToSegment}.
     * Only flat primitive fields and {@code String} are written; unsupported
     * field types are silently skipped. Any previous content in the segment
     * is overwritten.
     *
     * @param javaObject the object to serialize; its field types must be
     *                   supported by {@link StructMarshaller}
     */
    public void write(T javaObject) {
        StructMarshaller.writeToSegment(javaObject, this.segment, this.arena);
    }

    /**
     * Deserializes the current content of the native memory segment into a
     * new instance of {@code T}.
     *
     * <p>A new object is instantiated via the no-arg constructor of {@code T},
     * then its fields are populated from the segment using
     * {@link StructMarshaller#readFromSegment}. Typically called after a native
     * function has modified the struct's memory to read the updated values back.
     *
     * @return a new instance of {@code T} populated from the current segment content
     * @throws RuntimeException if {@code T} has no public no-arg constructor
     *                          or a field cannot be set
     */
    public T read() {
        return StructMarshaller.readFromSegment(this.type, this.segment);
    }

    /**
     * Returns the off-heap {@link MemorySegment} backing this struct.
     * Valid only while this struct is open (before {@link #close()} is called).
     *
     * @return the native memory segment
     */
    @Override
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Releases the off-heap memory by closing the underlying {@link Arena}.
     * After this call, {@link #segment()}, {@link #write}, and {@link #read}
     * must not be used.
     */
    @Override
    public void close() {
        arena.close();
    }
}