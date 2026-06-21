package org.example.memory;

import org.example.engine.NativeResource;
import org.example.config.MiteContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;

/**
 * A fixed-size off-heap array that provides zero-copy data exchange between
 * Java and native C++ code via Project Panama.
 *
 * <p>Unlike standard Java arrays which live on the JVM heap and must be copied
 * to off-heap memory before each native call, a {@code MiteArray} allocates
 * its backing memory outside the JVM heap using {@link Arena#ofConfined()}.
 * Native C++ code receives a direct pointer to this memory — no copying occurs
 * when a {@code MiteArray} is passed to {@link org.example.engine.CppEngine#execute}.
 *
 * <p>This makes {@code MiteArray} particularly valuable for workloads that call
 * native code repeatedly on the same dataset: the allocation cost is paid once
 * at construction, and subsequent calls operate directly on the shared memory.
 *
 * <p>Factory methods are provided for the most common element types:
 * <pre>{@code
 * try (MiteArray data = MiteArray.ofFloats(1_000_000)) {
 *     for (int i = 0; i < data.length(); i++) data.setFloat(i, rawData[i]);
 *
 *     for (int tick = 0; tick < 1000; tick++) {
 *         engine.execute("simulate_step", data, data.length());
 *         // C++ reads and writes data.segment() directly — no copy per call
 *     }
 * } // off-heap memory released here
 * }</pre>
 *
 * <p>Memory is aligned to the value returned by {@link MiteContext#getAlignmentBytes()}
 * at construction time. Set {@code mite.alignment-bytes=32} in {@code application.properties}
 * when using AVX2/AVX-512 instructions that require 256-bit aligned memory.
 *
 * <p>This class is not thread-safe. The underlying confined {@link Arena} may only
 * be accessed from the thread that created it.
 *
 * @see NativeResource
 * @see org.example.engine.CppEngine
 * @see MiteContext
 */
public class MiteArray implements NativeResource, AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int length;

    /**
     * Allocates a new {@code MiteArray} for {@code length} elements of the given layout.
     *
     * <p>Total byte size is {@code length * elementLayout.byteSize()}, aligned to
     * {@link MiteContext#getAlignmentBytes()}.
     *
     * @param length        the number of elements to allocate
     * @param elementLayout the Panama {@link MemoryLayout} describing a single element;
     *                      use {@link ValueLayout#JAVA_FLOAT}, {@link ValueLayout#JAVA_INT}, etc.
     */
    public MiteArray(int length, MemoryLayout elementLayout) {
        this.length = length;
        this.arena = Arena.ofConfined();
        long byteSize = (long) length * elementLayout.byteSize();

        int currentAlignment = MiteContext.getAlignmentBytes();

        this.segment = arena.allocate(byteSize, currentAlignment);
    }

    /**
     * Creates a {@code MiteArray} backed by {@code length} 32-bit floats ({@code float*} in C++).
     *
     * @param length number of {@code float} elements
     * @return a new {@code MiteArray} of floats
     */
    public static MiteArray ofFloats(int length) {
        return new MiteArray(length, ValueLayout.JAVA_FLOAT);
    }

    /**
     * Creates a {@code MiteArray} backed by {@code length} 32-bit integers ({@code int*} in C++).
     *
     * @param length number of {@code int} elements
     * @return a new {@code MiteArray} of ints
     */
    public static MiteArray ofInts(int length) {
        return new MiteArray(length, ValueLayout.JAVA_INT);
    }

    /**
     * Creates a {@code MiteArray} backed by {@code length} 64-bit doubles ({@code double*} in C++).
     *
     * @param length number of {@code double} elements
     * @return a new {@code MiteArray} of doubles
     */
    public static MiteArray ofDoubles(int length) {
        return new MiteArray(length, ValueLayout.JAVA_DOUBLE);
    }

    /**
     * Creates a {@code MiteArray} holding {@code strings.length} native string pointers
     * ({@code const char**} in C++).
     *
     * <p>Each string is allocated as a null-terminated native string inside the array's
     * own {@link Arena}. The lifetime of the string memory is tied to this array —
     * closing the array frees all string allocations.
     *
     * @param strings the Java strings to store; must not be {@code null}
     * @return a new {@code MiteArray} of string pointers
     */
    public static MiteArray ofStrings(String... strings) {
        MiteArray array = new MiteArray(strings.length, ValueLayout.ADDRESS);
        for (int i = 0; i < strings.length; i++) {
            MemorySegment cString = array.arena.allocateFrom(strings[i]);
            array.segment.setAtIndex(ValueLayout.ADDRESS, i, cString);
        }
        return array;
    }

    /**
     * Returns the {@code float} value at the given index.
     *
     * @param index zero-based element index
     * @return the float value stored at {@code index}
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public float getFloat(int index) {
        return segment.getAtIndex(ValueLayout.JAVA_FLOAT, index);
    }

    /**
     * Sets the {@code float} value at the given index.
     *
     * @param index zero-based element index
     * @param value the value to store
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public void setFloat(int index, float value) {
        segment.setAtIndex(ValueLayout.JAVA_FLOAT, index, value);
    }

    /**
     * Returns the {@code int} value at the given index.
     *
     * @param index zero-based element index
     * @return the int value stored at {@code index}
     */
    public int getInt(int index) {
        return segment.getAtIndex(ValueLayout.JAVA_INT, index);
    }

    /**
     * Sets the {@code int} value at the given index.
     *
     * @param index zero-based element index
     * @param value the value to store
     */
    public void setInt(int index, int value) {
        segment.setAtIndex(ValueLayout.JAVA_INT, index, value);
    }

    /**
     * Returns the {@code double} value at the given index.
     *
     * @param index zero-based element index
     * @return the double value stored at {@code index}
     */
    public double getDouble(int index) {
        return segment.getAtIndex(ValueLayout.JAVA_DOUBLE, index);
    }

    /**
     * Sets the {@code double} value at the given index.
     *
     * @param index zero-based element index
     * @param value the value to store
     */
    public void setDouble(int index, double value) {
        segment.setAtIndex(ValueLayout.JAVA_DOUBLE, index, value);
    }

    /**
     * Returns the number of elements in this array.
     *
     * @return element count
     */
    public int length() {
        return length;
    }

    /**
     * Returns the off-heap {@link MemorySegment} backing this array.
     *
     * <p>Passed directly to native functions as a pointer. Valid only while
     * this array is open (before {@link #close()} is called).
     *
     * @return the native memory segment
     */
    @Override
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Releases the off-heap memory owned by this array.
     *
     * <p>After this call, the segment returned by {@link #segment()} must not
     * be accessed. Native functions that still hold a pointer to this memory
     * will produce undefined behaviour if executed after close.
     */
    @Override
    public void close() {
        arena.close();
    }
}