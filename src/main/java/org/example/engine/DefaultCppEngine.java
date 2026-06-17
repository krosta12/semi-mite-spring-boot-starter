package org.example.engine;

import org.example.compiler.CppCompiler;
import org.example.compiler.MiteException;
import org.example.parser.FunctionSignature;
import org.example.scanner.FunctionRegistry;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public class DefaultCppEngine implements CppEngine {

    private final CppCompiler compiler;
    private final FunctionRegistry registry;
    private final Linker linker = Linker.nativeLinker();

    private final Path cacheFile = Path.of("cppScripts/compileCache", ".mite_cache.properties");
    private final Properties cache = new Properties();

    public DefaultCppEngine(CppCompiler compiler, FunctionRegistry registry) {
        this.compiler = compiler;
        this.registry = registry;
        loadCache();
    }

    @Override
    public Object execute(String functionName, Object... args) {
        final Object[] finalArgs = (args == null) ? new Object[]{null} : args;

        FunctionRegistry.ResolvedFunction resolved = registry.resolve(functionName, finalArgs)
                .orElseThrow(() -> {
                    String argTypes = java.util.Arrays.stream(finalArgs)
                            .map(arg -> arg == null ? "null" : arg.getClass().getSimpleName())
                            .collect(java.util.stream.Collectors.joining(", "));
                    return new MiteException("Function '" + functionName + "(" + argTypes + ")' not found.");
                });

        return invoke(resolved, finalArgs);
    }

    private Object invoke(FunctionRegistry.ResolvedFunction resolved, Object[] args) {
        Path libPath = getOrCompile(resolved.file());
        return invoke(libPath, resolved.signature(), args);
    }

    private synchronized Path getOrCompile(Path cppFile) {
        try {
            String key = cppFile.toAbsolutePath().toString();
            long currentModified = Files.getLastModifiedTime(cppFile).toMillis();

            String cachedValue = cache.getProperty(key);
            if (cachedValue != null && cachedValue.contains("|")) {
                int idx = cachedValue.lastIndexOf('|');
                String libPathStr = cachedValue.substring(0, idx);
                long cachedModified = Long.parseLong(cachedValue.substring(idx + 1));

                Path libPath = Path.of(libPathStr);

                if (currentModified == cachedModified && Files.exists(libPath)) {
                    return libPath;
                }
            }

            Path lib = compiler.compile(cppFile);

            cache.setProperty(key, lib.toAbsolutePath().toString() + "|" + currentModified);
            saveCache();

            return lib;
        } catch (IOException e) {
            throw new MiteException("Failed to handle compilation cache for: " + cppFile, e);
        }
    }

    private void loadCache() {
        if (!Files.exists(cacheFile)) return;
        try (var reader = Files.newBufferedReader(cacheFile)) {
            cache.load(reader);
        } catch (IOException e) {
            System.err.println("[MITE] Warning: Failed to load compilation cache: " + e.getMessage());
        }
    }

    private void saveCache() {
        try {
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            try (var writer = Files.newBufferedWriter(cacheFile)) {
                cache.store(writer, "Mite Persistent Compilation Cache");
            }
        } catch (IOException e) {
            System.err.println("[MITE] Warning: Failed to save compilation cache: " + e.getMessage());
        }
    }

    private Object invoke(Path lib, FunctionSignature sig, Object[] args) {
        if (args == null) {
            args = new Object[]{null};
        }

        SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());

        MemorySegment fn = lookup.find(sig.name())
                .orElseThrow(() -> new MiteException(
                        "Symbol '" + sig.name() + "' not found. Add extern \"C\" before the function."
                ));

        FunctionDescriptor descriptor = buildDescriptor(sig);
        MethodHandle handle = linker.downcallHandle(fn, descriptor);

        try (Arena arena = Arena.ofConfined()) {
            java.util.List<Runnable> copyBackTasks = new java.util.ArrayList<>();
            Object[] nativeArgs = marshalArgs(args, sig.paramTypes(), arena, copyBackTasks);

            Object result = null;
            if ("void".equals(sig.returnType())) {
                handle.invokeWithArguments(nativeArgs);
            } else {
                result = handle.invokeWithArguments(nativeArgs);
            }

            for (Runnable task : copyBackTasks) {
                task.run();
            }

            if ("void".equals(sig.returnType())) {
                return null;
            }
            return unmarshalResult(result, sig.returnType());
        } catch (Throwable e) {
            throw new MiteException("Error calling '" + sig.name() + "': " + e.getMessage(), e);
        }
    }

    private FunctionDescriptor buildDescriptor(FunctionSignature sig) {
        MemoryLayout returnLayout = toLayout(sig.returnType());
        List<MemoryLayout> paramLayouts = sig.paramTypes().stream()
                .map(this::toLayout)
                .toList();

        if (returnLayout == null) {
            return FunctionDescriptor.ofVoid(paramLayouts.toArray(new MemoryLayout[0]));
        }
        return FunctionDescriptor.of(returnLayout, paramLayouts.toArray(new MemoryLayout[0]));
    }

    private MemoryLayout toLayout(String cppType) {
        if (cppType != null && cppType.endsWith("*")) {
            return ValueLayout.ADDRESS;
        }

        return switch (cppType) {
            case "int8_t", "uint8_t", "char", "unsigned char" -> ValueLayout.JAVA_BYTE;
            case "int16_t", "uint16_t", "short", "unsigned short" -> ValueLayout.JAVA_SHORT;
            case "int", "unsigned int", "int32_t", "uint32_t" -> ValueLayout.JAVA_INT;
            case "long", "long long", "unsigned long long", "int64_t", "uint64_t" -> ValueLayout.JAVA_LONG;
            case "float" -> ValueLayout.JAVA_FLOAT;
            case "double" -> ValueLayout.JAVA_DOUBLE;
            case "bool" -> ValueLayout.JAVA_BOOLEAN;

            case "std::string", "const char*", "int*", "int32_t*", "long long*", "int64_t*", "double*", "float*" ->
                    ValueLayout.ADDRESS;
            case "void" -> null;

            default -> throw new MiteException("Unknown type: " + cppType);
        };
    }

    private Object[] marshalArgs(Object[] args, List<String> paramTypes, Arena arena, java.util.List<Runnable> copyBackTasks) {
        Object[] result = new Object[args.length];

        java.util.Map<Object, MemorySegment> seen = new java.util.IdentityHashMap<>();

        for (int i = 0; i < args.length; i++) {
            String type = paramTypes.get(i);

            if (args[i] instanceof NativeResource nativeRes) {
                result[i] = nativeRes.segment();
                continue;
            }

            if ("std::string".equals(type) || "const char*".equals(type)) {
                result[i] = args[i] == null ? MemorySegment.NULL : arena.allocateFrom((String) args[i]);
            } else if (type.endsWith("*")) {
                if (args[i] instanceof java.util.Collection) {
                    result[i] = allocateNativeArray((java.util.Collection<?>) args[i], type, arena);
                } else if (args[i] instanceof float[] arr) {
                    MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
                    result[i] = nativeSeg;
                    copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_FLOAT, 0, arr, 0, arr.length));
                } else if (args[i] instanceof int[] arr) {
                    MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_INT, arr);
                    result[i] = nativeSeg;
                    copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_INT, 0, arr, 0, arr.length));
                } else if (args[i] instanceof long[] arr) {
                    MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_LONG, arr);
                    result[i] = nativeSeg;
                    copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_LONG, 0, arr, 0, arr.length));
                } else if (args[i] instanceof double[] arr) {
                    MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, arr);
                    result[i] = nativeSeg;
                    copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_DOUBLE, 0, arr, 0, arr.length));
                } else {
                    result[i] = marshalCustomObject(args[i], arena, copyBackTasks, seen);
                }
            } else {
                result[i] = args[i];
            }
        }
        return result;
    }

    private Object unmarshalResult(Object result, String returnType) {
        if (result instanceof MemorySegment seg) {
            if (seg.address() == 0) {
                return null;
            }
            if ("const char*".equals(returnType) || "std::string".equals(returnType)) {
                MemorySegment safe = seg.reinterpret(4096);
                long len = 0;
                while (len < 4096 && safe.get(ValueLayout.JAVA_BYTE, len) != 0) len++;
                return new String(safe.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE));
            }
        }
        return result;
    }

    private MemorySegment allocateNativeArray(java.util.Collection<?> collection, String cppType, Arena arena) {
        int size = collection.size();
        return switch (cppType) {
            case "int*", "int32_t*" -> {
                int[] arr = collection.stream().mapToInt(x -> ((Number) x).intValue()).toArray();
                yield arena.allocateFrom(ValueLayout.JAVA_INT, arr);
            }
            case "long long*", "int64_t*" -> {
                long[] arr = collection.stream().mapToLong(x -> ((Number) x).longValue()).toArray();
                yield arena.allocateFrom(ValueLayout.JAVA_LONG, arr);
            }
            case "double*" -> {
                double[] arr = collection.stream().mapToDouble(x -> ((Number) x).doubleValue()).toArray();
                yield arena.allocateFrom(ValueLayout.JAVA_DOUBLE, arr);
            }
            case "float*" -> {
                float[] arr = new float[size];
                int i = 0;
                for (Object x : collection) {
                    arr[i++] = ((Number) x).floatValue();
                }
                yield arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
            }
            default -> throw new MiteException("Unsupported array type for marshalling: " + cppType);
        };
    }

    private MemorySegment marshalCustomObject(Object obj, Arena arena, java.util.List<Runnable> copyBackTasks, java.util.Map<Object, MemorySegment> seen) {
        if (obj == null) return MemorySegment.NULL;

        if (seen.containsKey(obj)) {
            return seen.get(obj);
        }

        try {
            java.lang.reflect.Field[] fields = obj.getClass().getDeclaredFields();
            long currentOffset = 0;
            long maxAlignment = 1;

            java.util.Map<java.lang.reflect.Field, Long> fieldOffsets = new java.util.HashMap<>();

            for (java.lang.reflect.Field field : fields) {
                if (field.isSynthetic()) continue;
                field.setAccessible(true);

                Class<?> fType = field.getType();
                long size;

                if (fType == int.class || fType == Integer.class) size = 4;
                else if (fType == long.class || fType == Long.class) size = 8;
                else if (fType == double.class || fType == Double.class) size = 8;
                else if (fType == float.class || fType == Float.class) size = 4;
                else if (fType == short.class || fType == Short.class) size = 2;
                else if (fType == boolean.class || fType == Boolean.class) size = 1;
                else if (fType == byte.class || fType == Byte.class) size = 1;
                else {
                    size = 8;
                }

                long alignment = size;
                if (alignment > maxAlignment) maxAlignment = alignment;

                currentOffset = (currentOffset + alignment - 1) & ~(alignment - 1);
                fieldOffsets.put(field, currentOffset);
                currentOffset += size;
            }

            long totalSize = (currentOffset + maxAlignment - 1) & ~(maxAlignment - 1);
            if (totalSize == 0) totalSize = 1;

            MemorySegment structSegment = arena.allocate(totalSize, maxAlignment);

            seen.put(obj, structSegment);

            for (java.lang.reflect.Field field : fields) {
                if (!fieldOffsets.containsKey(field)) continue;
                long offset = fieldOffsets.get(field);
                Object val = field.get(obj);

                Class<?> fType = field.getType();

                if (val == null) {
                    if (!fType.isPrimitive()) {
                        structSegment.set(ValueLayout.ADDRESS, offset, MemorySegment.NULL);
                    }
                    continue;
                }

                if (fType == int.class || fType == Integer.class) {
                    structSegment.set(ValueLayout.JAVA_INT, offset, ((Number) val).intValue());
                } else if (fType == long.class || fType == Long.class) {
                    structSegment.set(ValueLayout.JAVA_LONG, offset, ((Number) val).longValue());
                } else if (fType == double.class || fType == Double.class) {
                    structSegment.set(ValueLayout.JAVA_DOUBLE, offset, ((Number) val).doubleValue());
                } else if (fType == float.class || fType == Float.class) {
                    structSegment.set(ValueLayout.JAVA_FLOAT, offset, ((Number) val).floatValue());
                } else if (fType == short.class || fType == Short.class) {
                    structSegment.set(ValueLayout.JAVA_SHORT, offset, ((Number) val).shortValue());
                } else if (fType == boolean.class || fType == Boolean.class) {
                    structSegment.set(ValueLayout.JAVA_BOOLEAN, offset, (Boolean) val);
                } else if (fType == byte.class || fType == Byte.class) {
                    structSegment.set(ValueLayout.JAVA_BYTE, offset, ((Number) val).byteValue());
                }
                else if (fType == String.class) {
                    MemorySegment strSegment = arena.allocateFrom((String) val);
                    structSegment.set(ValueLayout.ADDRESS, offset, strSegment);
                }
                else if (fType == float[].class) {
                    float[] arr = (float[]) val;
                    MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                    copyBackTasks.add(() -> MemorySegment.copy(subSeg, ValueLayout.JAVA_FLOAT, 0, arr, 0, arr.length));
                } else if (fType == int[].class) {
                    int[] arr = (int[]) val;
                    MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_INT, arr);
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                    copyBackTasks.add(() -> MemorySegment.copy(subSeg, ValueLayout.JAVA_INT, 0, arr, 0, arr.length));
                } else if (fType == long[].class) {
                    long[] arr = (long[]) val;
                    MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_LONG, arr);
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                    copyBackTasks.add(() -> MemorySegment.copy(subSeg, ValueLayout.JAVA_LONG, 0, arr, 0, arr.length));
                } else if (fType == double[].class) {
                    double[] arr = (double[]) val;
                    MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, arr);
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                    copyBackTasks.add(() -> MemorySegment.copy(subSeg, ValueLayout.JAVA_DOUBLE, 0, arr, 0, arr.length));
                } else if (fType == byte[].class) {
                    byte[] arr = (byte[]) val;
                    MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, arr);
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                    copyBackTasks.add(() -> MemorySegment.copy(subSeg, ValueLayout.JAVA_BYTE, 0, arr, 0, arr.length));
                } else if (fType == boolean[].class) {
                    boolean[] arr = (boolean[]) val;
                    MemorySegment subSeg = arena.allocate(ValueLayout.JAVA_BYTE, arr.length);
                    for (int j = 0; j < arr.length; j++) {
                        subSeg.setAtIndex(ValueLayout.JAVA_BYTE, j, (byte) (arr[j] ? 1 : 0));
                    }
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);

                    copyBackTasks.add(() -> {
                        for (int j = 0; j < arr.length; j++) {
                            arr[j] = subSeg.getAtIndex(ValueLayout.JAVA_BYTE, j) != 0;
                        }
                    });}
                else if (fType.isArray()) {
                    Object[] arr = (Object[]) val;
                    MemorySegment subSeg = arena.allocate(ValueLayout.ADDRESS, arr.length);
                    for (int j = 0; j < arr.length; j++) {
                        MemorySegment elementSeg = marshalCustomObject(arr[j], arena, copyBackTasks, seen);
                        subSeg.setAtIndex(ValueLayout.ADDRESS, j, elementSeg);
                    }
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                }
                else if (val instanceof java.util.Collection) {
                    java.util.Collection<?> col = (java.util.Collection<?>) val;
                    if (!col.isEmpty()) {
                        Object first = col.iterator().next();
                        if (first instanceof Float) {
                            float[] arr = new float[col.size()]; int idx = 0; for (Object x : col) arr[idx++] = (Float) x;
                            MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
                            structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                            copyBackTasks.add(() -> { if (val instanceof java.util.List) { var l = (java.util.List<Float>) val; for (int k=0; k<arr.length; k++) l.set(k, subSeg.getAtIndex(ValueLayout.JAVA_FLOAT, k)); } });
                        } else if (first instanceof Integer) {
                            int[] arr = col.stream().mapToInt(x -> (Integer) x).toArray();
                            MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_INT, arr);
                            structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                            copyBackTasks.add(() -> { if (val instanceof java.util.List) { var l = (java.util.List<Integer>) val; for (int k=0; k<arr.length; k++) l.set(k, subSeg.getAtIndex(ValueLayout.JAVA_INT, k)); } });
                        } else {
                            MemorySegment subSeg = arena.allocate(ValueLayout.ADDRESS, col.size());
                            int idx = 0;
                            for (Object item : col) {
                                MemorySegment elementSeg = marshalCustomObject(item, arena, copyBackTasks, seen);
                                subSeg.setAtIndex(ValueLayout.ADDRESS, idx++, elementSeg);
                            }
                            structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                        }
                    } else {
                        structSegment.set(ValueLayout.ADDRESS, offset, MemorySegment.NULL);
                    }
                }
                else {
                    MemorySegment childSegment = marshalCustomObject(val, arena, copyBackTasks, seen);
                    structSegment.set(ValueLayout.ADDRESS, offset, childSegment);
                }
            }

            copyBackTasks.add(() -> {
                try {
                    for (java.lang.reflect.Field field : fields) {
                        if (!fieldOffsets.containsKey(field)) continue;
                        long offset = fieldOffsets.get(field);
                        Class<?> fType = field.getType();

                        if (fType == int.class || fType == Integer.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_INT, offset));
                        } else if (fType == long.class || fType == Long.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_LONG, offset));
                        } else if (fType == double.class || fType == Double.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_DOUBLE, offset));
                        } else if (fType == float.class || fType == Float.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_FLOAT, offset));
                        } else if (fType == short.class || fType == Short.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_SHORT, offset));
                        } else if (fType == boolean.class || fType == Boolean.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_BOOLEAN, offset));
                        } else if (fType == byte.class || fType == Byte.class) {
                            field.set(obj, structSegment.get(ValueLayout.JAVA_BYTE, offset));
                        }
                    }
                } catch (Exception e) {
                    throw new MiteException("Mite auto-unmarshal failed for object: " + obj.getClass().getName(), e);
                }
            });

            return structSegment;
        } catch (Exception e) {
            throw new MiteException("Failed to automatically marshal object to C-struct: " + obj.getClass().getName(), e);
        }
    }
}