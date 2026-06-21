package org.example.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;

/**
 * Low-level utility for reading and writing flat Java objects to and from
 * off-heap native memory segments.
 *
 * <p>This class handles only <b>flat structs</b> — objects whose fields are
 * exclusively scalar primitives or {@code String}. Nested objects, collections,
 * arrays of objects, and cyclic references are <b>not supported</b> here.
 * For those cases, use the recursive marshalling logic in
 * {@link org.example.engine.DefaultCppEngine}.
 *
 * <p>{@code StructMarshaller} is used by {@link MiteStruct} to back the
 * explicit {@link MiteStruct#write}/{@link MiteStruct#read} API. It is
 * not invoked by the automatic marshalling path in {@code DefaultCppEngine}.
 *
 * <p>Field layout follows natural C alignment rules: each field is aligned
 * to its own size (e.g., {@code float} to a 4-byte boundary, {@code long}
 * to an 8-byte boundary). Fields are processed in their declaration order.
 * Unsupported field types are silently skipped.
 *
 * <p>Supported field types:
 * <ul>
 *   <li>{@code int} / {@code Integer} — 4 bytes</li>
 *   <li>{@code long} / {@code Long} — 8 bytes</li>
 *   <li>{@code double} / {@code Double} — 8 bytes</li>
 *   <li>{@code float} / {@code Float} — 4 bytes</li>
 *   <li>{@code boolean} / {@code Boolean} — 1 byte</li>
 *   <li>{@code byte} / {@code Byte} — 1 byte</li>
 *   <li>{@code String} — 8-byte pointer to a null-terminated native string</li>
 * </ul>
 *
 * @see MiteStruct
 * @see StructLayoutCalculator
 */
public class StructMarshaller {

    /**
     * Writes the fields of {@code javaObject} into the given native memory segment.
     *
     * <p>Fields are laid out in declaration order using natural alignment.
     * Each field value is read via reflection and written to the corresponding
     * byte offset in {@code segment}. {@code String} values are allocated as
     * null-terminated native strings inside {@code arena}, and the resulting
     * pointer is stored in the segment. A {@code null} {@code String} field
     * writes a null pointer ({@link MemorySegment#NULL}).
     *
     * <p>Unsupported field types are silently skipped. The caller must ensure
     * {@code segment} is large enough to hold the struct — use
     * {@link StructLayoutCalculator#calculateSize} to determine the required size.
     *
     * @param <T>        the type of the Java object being written
     * @param javaObject the source Java object whose fields are serialised to native memory
     * @param segment    the target off-heap memory segment to write into
     * @param arena      the arena used to allocate native string memory for {@code String} fields;
     *                   must outlive any native reads of those string pointers
     * @throws RuntimeException if a field cannot be accessed via reflection
     */
    public static <T> void writeToSegment(T javaObject, MemorySegment segment, Arena arena) {
        try {
            Field[] fields = javaObject.getClass().getDeclaredFields();
            long currentOffset = 0;
            long maxAlignment = 1;

            for (Field field : fields) {
                if (field.isSynthetic()) continue;
                field.setAccessible(true);

                Class<?> fType = field.getType();
                long size;

                if (fType == int.class || fType == Integer.class) size = 4;
                else if (fType == long.class || fType == Long.class) size = 8;
                else if (fType == double.class || fType == Double.class) size = 8;
                else if (fType == float.class || fType == Float.class) size = 4;
                else if (fType == String.class) size = 8;
                else if (fType == boolean.class || fType == Boolean.class) size = 1;
                else if (fType == byte.class || fType == Byte.class) size = 1;
                else continue;

                long alignment = size;
                if (alignment > maxAlignment) maxAlignment = alignment;

                currentOffset = (currentOffset + alignment - 1) & -alignment;
                long offset = currentOffset;
                currentOffset += size;

                Object val = field.get(javaObject);
                if (val == null) {
                    if (fType == String.class) {
                        segment.set(ValueLayout.ADDRESS, offset, MemorySegment.NULL);
                    }
                    continue;
                }

                if (fType == int.class || fType == Integer.class) {
                    segment.set(ValueLayout.JAVA_INT, offset, ((Number) val).intValue());
                } else if (fType == long.class || fType == Long.class) {
                    segment.set(ValueLayout.JAVA_LONG, offset, ((Number) val).longValue());
                } else if (fType == double.class || fType == Double.class) {
                    segment.set(ValueLayout.JAVA_DOUBLE, offset, ((Number) val).doubleValue());
                } else if (fType == float.class || fType == Float.class) {
                    segment.set(ValueLayout.JAVA_FLOAT, offset, ((Number) val).floatValue());
                } else if (fType == boolean.class || fType == Boolean.class) {
                    segment.set(ValueLayout.JAVA_BOOLEAN, offset, (Boolean) val);
                } else if (fType == byte.class || fType == Byte.class) {
                    segment.set(ValueLayout.JAVA_BYTE, offset, ((Number) val).byteValue());
                } else if (fType == String.class) {
                    MemorySegment strSegment = arena.allocateFrom((String) val);
                    segment.set(ValueLayout.ADDRESS, offset, strSegment);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to write struct to off-heap", e);
        }
    }

    /**
     * Reads fields from the given native memory segment into a new instance of {@code type}.
     *
     * <p>The class is instantiated via its no-arg constructor. Fields are then populated
     * in declaration order using the same natural-alignment layout algorithm as
     * {@link #writeToSegment}, ensuring byte offsets match exactly.
     *
     * <p>{@code String} fields are read by following the stored native pointer and scanning
     * for a null terminator, with a safety cap of 4096 bytes. A null pointer results in
     * {@code null} being set on the field. Unsupported field types are silently skipped.
     *
     * @param <T>     the type to instantiate and populate
     * @param type    the class whose fields should be read from native memory;
     *                must have a public no-arg constructor
     * @param segment the source off-heap memory segment to read from
     * @return a new instance of {@code type} with fields populated from {@code segment}
     * @throws RuntimeException if the object cannot be instantiated or a field cannot be set
     */
    public static <T> T readFromSegment(Class<T> type, MemorySegment segment) {
        try {
            T obj = type.getConstructor().newInstance();
            Field[] fields = type.getDeclaredFields();
            long currentOffset = 0;
            long maxAlignment = 1;

            for (Field field : fields) {
                if (field.isSynthetic()) continue;
                field.setAccessible(true);

                Class<?> fType = field.getType();
                long size;

                if (fType == int.class || fType == Integer.class) size = 4;
                else if (fType == long.class || fType == Long.class) size = 8;
                else if (fType == double.class || fType == Double.class) size = 8;
                else if (fType == float.class || fType == Float.class) size = 4;
                else if (fType == String.class) size = 8;
                else if (fType == boolean.class || fType == Boolean.class) size = 1;
                else if (fType == byte.class || fType == Byte.class) size = 1;
                else continue;

                long alignment = size;
                if (alignment > maxAlignment) maxAlignment = alignment;

                currentOffset = (currentOffset + alignment - 1) & -alignment;
                long offset = currentOffset;
                currentOffset += size;

                if (fType == int.class || fType == Integer.class) {
                    field.set(obj, segment.get(ValueLayout.JAVA_INT, offset));
                } else if (fType == long.class || fType == Long.class) {
                    field.set(obj, segment.get(ValueLayout.JAVA_LONG, offset));
                } else if (fType == double.class || fType == Double.class) {
                    field.set(obj, segment.get(ValueLayout.JAVA_DOUBLE, offset));
                } else if (fType == float.class || fType == Float.class) {
                    field.set(obj, segment.get(ValueLayout.JAVA_FLOAT, offset));
                } else if (fType == boolean.class || fType == Boolean.class) {
                    field.set(obj, segment.get(ValueLayout.JAVA_BOOLEAN, offset));
                } else if (fType == byte.class || fType == Byte.class) {
                    field.set(obj, segment.get(ValueLayout.JAVA_BYTE, offset));
                } else {
                    MemorySegment addr = segment.get(ValueLayout.ADDRESS, offset);
                    if (addr.address() == 0) {
                        field.set(obj, null);
                    } else {
                        MemorySegment safe = addr.reinterpret(4096);
                        long len = 0;
                        while (len < 4096 && safe.get(ValueLayout.JAVA_BYTE, len) != 0) len++;
                        field.set(obj, new String(safe.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE)));
                    }
                }
            }
            return obj;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read struct from off-heap", e);
        }
    }
}