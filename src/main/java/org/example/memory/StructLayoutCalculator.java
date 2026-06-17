package org.example.memory;

import java.lang.reflect.Field;

public class StructLayoutCalculator {

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

            currentOffset = (currentOffset + alignment - 1) & ~(alignment - 1);
            currentOffset += size;
        }

        long totalSize = (currentOffset + maxAlignment - 1) & ~(maxAlignment - 1);
        return totalSize == 0 ? 1 : totalSize;
    }
}