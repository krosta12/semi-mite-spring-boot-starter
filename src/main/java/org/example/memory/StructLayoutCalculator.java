package org.example.memory;

import java.lang.reflect.Field;

/**
 * Calculates the total native memory size required to hold a flat Java struct,
 * using the same natural-alignment rules applied by {@link StructMarshaller}.
 *
 * <p>The size calculation mirrors standard C struct layout:
 * <ul>
 *   <li>Fields are processed in declaration order.</li>
 *   <li>Each field is aligned to its own natural size boundary
 *       (e.g., {@code float} to 4 bytes, {@code long} to 8 bytes).</li>
 *   <li>The total struct size is padded to the alignment of its largest field,
 *       so that arrays of structs remain correctly aligned.</li>
 * </ul>
 *
 * <p>This calculator is used by {@link MiteStruct} to determine how much off-heap
 * memory to allocate before {@link StructMarshaller} writes the field values.
 * The same alignment formula is used in both classes, ensuring that field offsets
 * are consistent between allocation and write.
 *
 * <p>Unsupported field types (anything not in the list below) are silently skipped
 * and contribute nothing to the calculated size. The supported types match those
 * handled by {@link StructMarshaller}:
 * <ul>
 *   <li>{@code int} / {@code Integer} — 4 bytes</li>
 *   <li>{@code long} / {@code Long} — 8 bytes</li>
 *   <li>{@code double} / {@code Double} — 8 bytes</li>
 *   <li>{@code float} / {@code Float} — 4 bytes</li>
 *   <li>{@code String} — 8 bytes (pointer size)</li>
 *   <li>{@code boolean} / {@code Boolean} — 1 byte</li>
 *   <li>{@code byte} / {@code Byte} — 1 byte</li>
 * </ul>
 *
 * <p>If the computed size is zero (e.g., the class has no supported fields),
 * a minimum of 1 byte is returned to ensure a valid, non-zero allocation.
 *
 * @see StructMarshaller
 * @see MiteStruct
 */
public class StructLayoutCalculator {

    /**
     * Calculates the total native memory size in bytes required to hold one
     * instance of {@code type} when laid out according to natural C alignment rules.
     *
     * <p>The result is the minimum size that {@link MiteStruct} should allocate
     * before calling {@link StructMarshaller#writeToSegment}. Passing a smaller
     * segment will result in out-of-bounds writes.
     *
     * @param type the Java class whose native struct size should be calculated;
     *             only declared (non-synthetic) fields of supported types are counted
     * @return the total aligned size in bytes; at least {@code 1}
     */
    public static long calculateSize(Class<?> type) {
        Field[] fields = type.getDeclaredFields();
        long currentOffset = 0;
        long maxAlignment = 1;

        for (Field field : fields) {
            if (field.isSynthetic()) continue;

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
            currentOffset += size;
        }

        long totalSize = (currentOffset + maxAlignment - 1) & -maxAlignment;
        return totalSize == 0 ? 1 : totalSize;
    }
}