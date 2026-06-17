package org.example.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;

public class StructMarshaller {

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

                currentOffset = (currentOffset + alignment - 1) & ~(alignment - 1);
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

                currentOffset = (currentOffset + alignment - 1) & ~(alignment - 1);
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
                } else if (fType == String.class) {
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