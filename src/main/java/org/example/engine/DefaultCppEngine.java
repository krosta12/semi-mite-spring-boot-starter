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
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link CppEngine} that resolves, compiles, and invokes
 * native C++ functions via Project Panama's Foreign Function & Memory API.
 *
 * <h2>Call lifecycle</h2>
 * <ol>
 *   <li><b>Resolution</b> - {@link FunctionRegistry#resolve} matches the function name
 *       and argument types against registered {@code // @mite}-marked signatures.</li>
 *   <li><b>Compilation</b> - {@link #getOrCompile} checks the two-level cache (in-memory
 *       + persistent {@code .mite_cache.properties}) and only invokes {@link CppCompiler}
 *       when the source file has changed.</li>
 *   <li><b>Linking</b> - a Panama {@link MethodHandle} is created once per
 *       {@code (library, signature)} pair and cached in {@link #methodHandleCache}.</li>
 *   <li><b>Marshalling</b> - {@link #marshalArgs} converts Java arguments to native
 *       memory. Primitive arrays are copied to off-heap; {@link NativeResource} instances
 *       are passed as direct pointers; {@link org.example.annotation.MiteStruct} objects
 *       are recursively serialized with cycle detection via {@code IdentityHashMap}.</li>
 *   <li><b>Invocation</b> - the downcall handle is invoked inside a confined {@link Arena}
 *       that is closed (and all scoped memory freed) when the call returns.</li>
 *   <li><b>Copy-back</b> - tasks registered during marshalling copy modified native memory
 *       back to the original Java arrays and objects.</li>
 *   <li><b>Unmarshalling</b> - return values are converted from native types back to Java.</li>
 * </ol>
 *
 * <h2>Caching strategy</h2>
 * <ul>
 *   <li><b>Library cache</b> ({@link #libraryCache}): {@code Path → SymbolLookup}.
 *       Each compiled {@code .dll}/{@code .so} is loaded once into {@link Arena#global()}.</li>
 *   <li><b>Method handle cache</b> ({@link #methodHandleCache}): keyed on
 *       {@code libPath::functionName::paramTypes->returnType}. Panama downcall handles
 *       are expensive to create; caching them eliminates repeated descriptor building
 *       and linker lookups on hot paths.</li>
 *   <li><b>Compilation cache</b> ({@link #cache} + {@link #cacheFile}): persists
 *       {@code sourcePath → (libPath|lastModified)} across process restarts so that
 *       unchanged sources are never recompiled.</li>
 * </ul>
 *
 * <h2>Cyclic graph support</h2>
 * <p>Object identity is tracked during marshalling via an {@link java.util.IdentityHashMap}
 * ({@code seen}: Java object → native segment). If the same object is encountered again
 * during recursive traversal, its previously allocated native address is reused, breaking
 * the cycle. The reverse map ({@code nativeToJava}: native address → Java object) is used
 * during copy-back and unmarshalling to restore the same Java instances.
 *
 * @see CppEngine
 * @see CppCompiler
 * @see FunctionRegistry
 * @see org.example.memory.MiteArray
 */
public class DefaultCppEngine implements CppEngine {

    private final CppCompiler compiler;
    private final FunctionRegistry registry;
    private final Linker linker = Linker.nativeLinker();

    /**
     * Path to the persistent compilation cache file, stored alongside compiled libraries.
     * Survives process restarts and avoids recompilation of unchanged sources.
     */
    private final Path cacheFile;
    /**
     * Persistent key-value store: {@code sourcePath → libPath|lastModified}.
     */
    private final Properties cache = new Properties();

    /**
     * Maps compiled library paths to their loaded {@link SymbolLookup} instances.
     */
    private final java.util.Map<Path, SymbolLookup> libraryCache = new ConcurrentHashMap<>();
    /**
     * Maps {@code "libPath::functionName::paramTypes->returnType"} to cached
     * {@link MethodHandle} instances. Eliminates repeated Panama linker overhead on hot paths.
     */
    private final java.util.Map<String, MethodHandle> methodHandleCache = new ConcurrentHashMap<>();

    /**
     * Runtime registry of Java classes seen during marshalling, keyed by their simple name.
     * Used to resolve pointer return types (e.g., {@code User*}) back to their Java class
     * during unmarshalling of return values.
     */
    private final java.util.Map<String, Class<?>> structRegistry = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code DefaultCppEngine} wired to the given compiler and function registry.
     * Loads the persistent compilation cache from disk if it exists.
     *
     * @param compiler the {@link CppCompiler} used to build native shared libraries
     * @param registry the {@link FunctionRegistry} used to resolve function signatures by name
     */
    public DefaultCppEngine(CppCompiler compiler, FunctionRegistry registry, Path scriptsDir) {
        this.compiler = compiler;
        this.registry = registry;
        this.cacheFile = scriptsDir.resolve("compileCache").resolve(".mite_cache.properties");
        loadCache();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolves {@code functionName} against the function registry using the
     * runtime types of {@code args} for overload matching. If no matching signature
     * is found, a {@link MiteException} is thrown with a human-readable message
     * listing the argument types that were tried.
     */
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

    /**
     * Ensures the source file backing {@code resolved} is compiled, then dispatches
     * the native call.
     *
     * @param resolved the matched native function with its source file and signature
     * @param args     the Java arguments to pass
     * @return the unmarshalled return value, or {@code null} for void functions
     */
    private Object invoke(FunctionRegistry.ResolvedFunction resolved, Object[] args) {
        Path libPath = getOrCompile(resolved.file());
        return invoke(libPath, resolved.signature(), args);
    }

    /**
     * Returns the path to the compiled shared library for {@code cppFile},
     * compiling it if necessary.
     *
     * <p>Two cache levels are checked in order:
     * <ol>
     *   <li>The persistent {@link #cache}: if a valid entry exists and the library
     *       file is still present on disk, it is returned immediately.</li>
     *   <li>{@link CppCompiler#compile}: invoked only when no valid cache entry exists
     *       or the source file has been modified. The new entry is written back to the
     *       persistent cache on disk.</li>
     * </ol>
     *
     * @param cppFile the {@code .cpp} source file to compile
     * @return path to the compiled {@code .dll} or {@code .so} library
     * @throws MiteException if the last-modified time cannot be read or compilation fails
     */
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

            cache.setProperty(key, lib.toAbsolutePath() + "|" + currentModified);
            saveCache();

            return lib;
        } catch (IOException e) {
            throw new MiteException("Failed to handle compilation cache for: " + cppFile, e);
        }
    }

    /**
     * Loads the persistent compilation cache from {@link #cacheFile} into {@link #cache}.
     * If the file does not exist, the cache starts empty and is populated on first compile.
     */
    private void loadCache() {
        if (!Files.exists(cacheFile)) return;
        try (var reader = Files.newBufferedReader(cacheFile)) {
            cache.load(reader);
        } catch (IOException e) {
            System.err.println("[MITE] Warning: Failed to load compilation cache: " + e.getMessage());
        }
    }

    /**
     * Persists the current compilation cache to {@link #cacheFile}.
     * Parent directories are created if they do not exist.
     * Failures are logged as warnings but do not interrupt the call.
     */
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

    /**
     * Core invocation method. Looks up or creates a {@link MethodHandle} for the function,
     * marshals arguments, invokes the handle, runs copy-back tasks, and unmarshals
     * the return value.
     *
     * <p>All scoped off-heap memory is allocated inside a {@link Arena#ofConfined()} that
     * is closed at the end of this method, releasing all temporary native allocations
     * made during argument marshalling. Memory allocated for {@link NativeResource}
     * instances is managed by those resources and is not released here.
     *
     * @param lib  path to the compiled shared library
     * @param sig  the native function signature (name, param types, return type)
     * @param args the Java arguments to pass to the function
     * @return the unmarshalled return value, or {@code null} for void functions
     * @throws MiteException if Panama invocation fails or marshalling errors occur
     */
    private Object invoke(Path lib, FunctionSignature sig, Object[] args) {
        if (args == null) {
            args = new Object[]{null};
        }

        String cacheKey = lib.toAbsolutePath() + "::" + sig.name() + "::" + sig.paramTypes().toString() + "->" + sig.returnType();
        MethodHandle handle = methodHandleCache.computeIfAbsent(cacheKey, k -> {
            SymbolLookup lookup = libraryCache.computeIfAbsent(lib, p -> SymbolLookup.libraryLookup(p, Arena.global()));
            MemorySegment fn = lookup.find(sig.name())
                    .orElseThrow(() -> new MiteException(
                            "Symbol '" + sig.name() + "' not found. Add extern \"C\" before the function."
                    ));
            FunctionDescriptor descriptor = buildDescriptor(sig);
            return linker.downcallHandle(fn, descriptor);
        });

        try (Arena arena = Arena.ofConfined()) {
            java.util.List<Runnable> copyBackTasks = new java.util.ArrayList<>();

            java.util.Map<Object, MemorySegment> seen = new java.util.IdentityHashMap<>();
            java.util.Map<Long, Object> nativeToJava = new java.util.HashMap<>();

            for (Object arg : args) {
                if (arg != null && arg.getClass().isAnnotationPresent(org.example.annotation.MiteStruct.class)) {
                    structRegistry.put(arg.getClass().getSimpleName(), arg.getClass());
                }
            }

            Object[] nativeArgs = marshalArgs(args, sig.paramTypes(), arena, copyBackTasks, seen, nativeToJava);

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

            return unmarshalResult(result, sig.returnType(), nativeToJava);
        } catch (Throwable e) {
            throw new MiteException("Error calling '" + sig.name() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Builds a Panama {@link FunctionDescriptor} from the given function signature.
     *
     * <p>Each C++ type string is mapped to a {@link MemoryLayout} via {@link #toLayout}.
     * Pointer types ({@code T*}) always map to {@link ValueLayout#ADDRESS}.
     * Void return types produce a {@link FunctionDescriptor#ofVoid} descriptor.
     *
     * @param sig the parsed native function signature
     * @return a {@link FunctionDescriptor} suitable for Panama downcall linking
     */
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

    /**
     * Maps a C++ type string to the corresponding Panama {@link MemoryLayout}.
     *
     * <p>All pointer types (strings ending with {@code *}) map to {@link ValueLayout#ADDRESS}.
     * {@code void} maps to {@code null}, which signals a void return in {@link #buildDescriptor}.
     *
     * @param cppType the C++ type string as parsed from the source file
     * @return the corresponding {@link MemoryLayout}, or {@code null} for {@code void}
     * @throws MiteException if the type is not recognised
     */
    private MemoryLayout toLayout(String cppType) {
        if (cppType != null && cppType.endsWith("*")) {
            return ValueLayout.ADDRESS;
        }

        assert cppType != null;
        return switch (cppType) {
            case "int8_t", "uint8_t", "char", "unsigned char" -> ValueLayout.JAVA_BYTE;
            case "int16_t", "uint16_t", "short", "unsigned short" -> ValueLayout.JAVA_SHORT;
            case "int", "unsigned int", "int32_t", "uint32_t" -> ValueLayout.JAVA_INT;
            case "long", "long long", "unsigned long long", "int64_t", "uint64_t" -> ValueLayout.JAVA_LONG;
            case "float" -> ValueLayout.JAVA_FLOAT;
            case "double" -> ValueLayout.JAVA_DOUBLE;
            case "bool" -> ValueLayout.JAVA_BOOLEAN;

            case "std::string", "const char*", "int*", "int32_t*", "long long*", "int64_t*", "double*", "float*",
                 "bool*" -> ValueLayout.ADDRESS;
            case "void" -> null;

            default -> throw new MiteException("Unknown type: " + cppType);
        };
    }

    /**
     * Marshals all Java arguments to their native equivalents for the downcall.
     *
     * <p>Conversion rules per argument:
     * <ul>
     *   <li>{@link NativeResource} - segment passed directly, no copy.</li>
     *   <li>{@code String} / {@code const char*} - null-terminated native string allocated in the arena.</li>
     *   <li>Primitive arrays ({@code float[]}, {@code int[]}, etc.) - copied to off-heap;
     *       a copy-back task is registered to reflect C++ modifications back to the Java array.</li>
     *   <li>{@code boolean[]} - converted element-by-element to {@code byte} (1/0), copied back after the call.</li>
     *   <li>{@link java.util.Collection} - forwarded to {@link #allocateNativeArray}.</li>
     *   <li>{@link org.example.annotation.MiteStruct}-annotated objects - recursively marshalled
     *       via {@link #marshalCustomObject}; modifications written back via copy-back tasks.</li>
     *   <li>All other pointer types without a matching rule fall through to {@code marshalCustomObject}.</li>
     *   <li>Primitive scalars - passed through unchanged.</li>
     * </ul>
     *
     * @param args          Java arguments matching the function signature
     * @param paramTypes    C++ parameter type strings from the parsed signature
     * @param arena         confined arena for all temporary native allocations in this call
     * @param copyBackTasks mutable list; implementations append tasks that copy native memory
     *                      back to Java objects after the native call completes
     * @param seen          identity map tracking Java objects already marshalled in this call,
     *                      used to break cyclic references
     * @param nativeToJava  map from native segment address to the Java object it represents,
     *                      used during copy-back to restore the correct Java instances
     * @return array of native-ready arguments suitable for {@link MethodHandle#invokeWithArguments}
     */
    private Object[] marshalArgs(Object[] args, List<String> paramTypes, Arena arena,
                                 java.util.List<Runnable> copyBackTasks,
                                 java.util.Map<Object, MemorySegment> seen,
                                 java.util.Map<Long, Object> nativeToJava) {
        Object[] result = new Object[args.length];

        for (int i = 0; i < args.length; i++) {
            String type = paramTypes.get(i);

            if (args[i] instanceof NativeResource nativeRes) {
                result[i] = nativeRes.segment();
                continue;
            }

            if ("std::string".equals(type) || "const char*".equals(type)) {
                result[i] = args[i] == null ? MemorySegment.NULL : arena.allocateFrom((String) args[i]);
            } else if (type != null && type.endsWith("*")) {
                switch (args[i]) {
                    case java.util.Collection collection -> result[i] = allocateNativeArray(collection, type, arena);
                    case float[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_FLOAT, 0, arr, 0, arr.length));
                    }
                    case int[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_INT, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_INT, 0, arr, 0, arr.length));
                    }
                    case long[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_LONG, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_LONG, 0, arr, 0, arr.length));
                    }
                    case double[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_DOUBLE, 0, arr, 0, arr.length));
                    }
                    case boolean[] arr -> {
                        MemorySegment nativeSeg = arena.allocate(ValueLayout.JAVA_BYTE, arr.length);
                        for (int j = 0; j < arr.length; j++) {
                            nativeSeg.setAtIndex(ValueLayout.JAVA_BYTE, j, (byte) (arr[j] ? 1 : 0));
                        }
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> {
                            for (int j = 0; j < arr.length; j++) {
                                arr[j] = nativeSeg.getAtIndex(ValueLayout.JAVA_BYTE, j) != 0;
                            }
                        });
                    }
                    case byte[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_BYTE, 0, arr, 0, arr.length));
                    }
                    case short[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_SHORT, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_SHORT, 0, arr, 0, arr.length));
                    }
                    case char[] arr -> {
                        MemorySegment nativeSeg = arena.allocateFrom(ValueLayout.JAVA_CHAR, arr);
                        result[i] = nativeSeg;
                        copyBackTasks.add(() -> MemorySegment.copy(nativeSeg, ValueLayout.JAVA_CHAR, 0, arr, 0, arr.length));
                    }
                    case null, default ->
                            result[i] = marshalCustomObject(args[i], arena, copyBackTasks, seen, nativeToJava);
                }
            } else {
                result[i] = args[i];
            }
        }
        return result;
    }

    /**
     * Converts a native return value back to its Java equivalent.
     *
     * <p>For pointer return types, attempts to reconstruct the Java object from native memory:
     * <ul>
     *   <li>{@code const char*} / {@code std::string} - reads a null-terminated string.</li>
     *   <li>Custom struct pointer - looks up the class in {@link #structRegistry} and delegates
     *       to {@link #unmarshalCustomObject}.</li>
     * </ul>
     * Non-pointer primitives are returned as-is.
     *
     * @param result       the raw value returned by the Panama downcall handle
     * @param returnType   the C++ return type string from the function signature
     * @param nativeToJava map used to resolve previously seen native addresses to Java objects
     * @return the Java-typed return value
     */
    private Object unmarshalResult(Object result, String returnType, java.util.Map<Long, Object> nativeToJava) {
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

            if (returnType != null && returnType.endsWith("*")) {
                String cleanType = returnType.replace("*", "").trim();
                Class<?> targetClass = structRegistry.get(cleanType);
                if (targetClass != null) {
                    return unmarshalCustomObject(seg, targetClass, nativeToJava);
                }
            }
        }
        return result;
    }

    /**
     * Reads a native memory segment back into a Java object of the given class.
     *
     * <p>Field offsets are recalculated using the same natural-alignment algorithm used
     * during marshalling, so that reads land at the same byte positions as the original writes.
     *
     * <p>Cycle detection is performed via {@code nativeToJava}: if the segment address has
     * already been mapped to a Java object during this call, that existing object is returned
     * immediately without re-reading, preserving reference identity across the object graph.
     *
     * @param seg          the native memory segment to read from
     * @param clazz        the Java class to instantiate and populate
     * @param nativeToJava map from native address to Java object, used for cycle breaking
     * @return a new instance of {@code clazz} populated from the native memory,
     * or the existing Java object if this address was already seen
     * @throws MiteException if the object cannot be instantiated or a field cannot be set
     */
    private Object unmarshalCustomObject(MemorySegment seg, Class<?> clazz, java.util.Map<Long, Object> nativeToJava) {
        if (seg.address() == 0) return null;
        if (nativeToJava.containsKey(seg.address())) return nativeToJava.get(seg.address());

        try {
            Object obj = clazz.getDeclaredConstructor().newInstance();
            nativeToJava.put(seg.address(), obj);

            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            long currentOffset = 0;
            long maxAlignment = 1;
            java.util.Map<java.lang.reflect.Field, Long> fieldOffsets = new java.util.HashMap<>();

            for (java.lang.reflect.Field field : fields) {
                if (field.isSynthetic()) continue;
                field.setAccessible(true);
                Class<?> fType = field.getType();
                long size = (fType == int.class || fType == float.class) ? 4 :
                        (fType == long.class || fType == double.class) ? 8 :
                                (fType == short.class) ? 2 :
                                        (fType == boolean.class || fType == byte.class) ? 1 : 8;

                if (size > maxAlignment) maxAlignment = size;
                currentOffset = (currentOffset + size - 1) & -size;
                fieldOffsets.put(field, currentOffset);
                currentOffset += size;
            }

            for (java.lang.reflect.Field field : fields) {
                if (!fieldOffsets.containsKey(field)) continue;
                long offset = fieldOffsets.get(field);
                Class<?> fType = field.getType();

                if (fType == int.class || fType == Integer.class) field.set(obj, seg.get(ValueLayout.JAVA_INT, offset));
                else if (fType == long.class || fType == Long.class)
                    field.set(obj, seg.get(ValueLayout.JAVA_LONG, offset));
                else if (fType == double.class || fType == Double.class)
                    field.set(obj, seg.get(ValueLayout.JAVA_DOUBLE, offset));
                else if (fType == float.class || fType == Float.class)
                    field.set(obj, seg.get(ValueLayout.JAVA_FLOAT, offset));
                else if (fType == short.class || fType == Short.class)
                    field.set(obj, seg.get(ValueLayout.JAVA_SHORT, offset));
                else if (fType == boolean.class || fType == Boolean.class)
                    field.set(obj, seg.get(ValueLayout.JAVA_BOOLEAN, offset));
                else if (fType == byte.class || fType == Byte.class)
                    field.set(obj, seg.get(ValueLayout.JAVA_BYTE, offset));
                else if (fType == String.class) {
                    MemorySegment strSeg = seg.get(ValueLayout.ADDRESS, offset);
                    if (strSeg.address() != 0) {
                        MemorySegment safe = strSeg.reinterpret(Long.MAX_VALUE);
                        long len = 0;
                        while (safe.get(ValueLayout.JAVA_BYTE, len) != 0) len++;
                        field.set(obj, new String(safe.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE)));
                    }
                } else if (!fType.isPrimitive() && !fType.isArray() && !java.util.Collection.class.isAssignableFrom(fType)) {
                    MemorySegment childSeg = seg.get(ValueLayout.ADDRESS, offset);
                    field.set(obj, unmarshalCustomObject(childSeg, fType, nativeToJava));
                }
            }
            return obj;
        } catch (Exception e) {
            throw new MiteException("Dynamic unmarshalling failed for " + clazz.getName(), e);
        }
    }

    /**
     * Allocates a native array from a Java {@link java.util.Collection} of numeric values.
     *
     * <p>Supported C++ target types: {@code int*}, {@code int32_t*}, {@code long long*},
     * {@code int64_t*}, {@code double*}, {@code float*}. Collection elements are cast
     * to the appropriate numeric type via {@link Number}.
     *
     * @param collection the Java collection to convert
     * @param cppType    the C++ pointer type string indicating the element type
     * @param arena      the arena to allocate the native array in
     * @return a native {@link MemorySegment} containing the collection elements
     * @throws MiteException if {@code cppType} is not a supported numeric array type
     */
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
            case "double*", "float*" -> {
                if (cppType.contains("double")) {
                    double[] arr = collection.stream().mapToDouble(x -> ((Number) x).doubleValue()).toArray();
                    yield arena.allocateFrom(ValueLayout.JAVA_DOUBLE, arr);
                } else {
                    float[] arr = new float[size];
                    int i = 0;
                    for (Object x : collection) {
                        arr[i++] = ((Number) x).floatValue();
                    }
                    yield arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
                }
            }
            default -> throw new MiteException("Unsupported array type for marshalling: " + cppType);
        };
    }

    /**
     * Recursively marshals a {@link org.example.annotation.MiteStruct}-annotated Java object
     * into a native memory segment whose layout matches the corresponding C++ struct.
     *
     * <p>Layout algorithm: fields are visited in declaration order. Each field is aligned
     * to its natural size boundary (e.g., {@code float} to 4 bytes, {@code long} to 8 bytes).
     * The total struct size is padded to the largest field alignment, matching standard
     * C++ struct layout rules for most compilers.
     *
     * <p>Cyclic references are broken using the {@code seen} identity map: if this Java
     * object has already been marshalled in this call, the previously allocated segment
     * is returned immediately. This also ensures that two Java references to the same object
     * produce the same native pointer on the C++ side.
     *
     * <p>After the native call completes, a copy-back task registered by this method
     * reads all scalar fields from the segment back into the Java object, reflecting any
     * modifications made by C++ code.
     *
     * <p>Nested objects and collections are marshalled recursively. Supported field types:
     * primitive scalars, {@code String}, primitive arrays ({@code float[]}, {@code int[]},
     * {@code long[]}, {@code double[]}, {@code byte[]}, {@code boolean[]}), object arrays,
     * {@link java.util.Collection} of {@code Float}/{@code Integer}/custom structs,
     * and nested {@code @MiteStruct} objects.
     *
     * @param obj           the Java object to marshal; must be annotated with
     *                      {@link org.example.annotation.MiteStruct}
     * @param arena         the confined arena used for all native allocations in this call
     * @param copyBackTasks mutable list to which the copy-back task for this object is appended
     * @param seen          identity map of Java objects already marshalled, keyed by object identity
     * @param nativeToJava  map from native address to Java object, populated during marshalling
     *                      and used during copy-back to restore correct Java references
     * @return the native {@link MemorySegment} representing the marshalled struct
     * @throws MiteException if the object is not annotated with {@code @MiteStruct},
     *                       if a field cannot be accessed, or if marshalling fails
     */
    @SuppressWarnings("unchecked")
    private MemorySegment marshalCustomObject(Object obj, Arena arena,
                                              java.util.List<Runnable> copyBackTasks,
                                              java.util.Map<Object, MemorySegment> seen,
                                              java.util.Map<Long, Object> nativeToJava) {
        if (obj == null) return MemorySegment.NULL;
        if (seen.containsKey(obj)) return seen.get(obj);

        if (!obj.getClass().isAnnotationPresent(org.example.annotation.MiteStruct.class)) {
            throw new MiteException("Security Violation: Class " + obj.getClass().getName() +
                    " must be annotated with @MiteStruct to be passed automatically.");
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

                currentOffset = (currentOffset + alignment - 1) & -alignment;
                fieldOffsets.put(field, currentOffset);
                currentOffset += size;
            }

            long totalSize = (currentOffset + maxAlignment - 1) & -maxAlignment;
            if (totalSize == 0) totalSize = 1;

            MemorySegment structSegment = arena.allocate(totalSize, maxAlignment);
            seen.put(obj, structSegment);
            nativeToJava.put(structSegment.address(), obj);

            structRegistry.put(obj.getClass().getSimpleName(), obj.getClass());

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
                } else if (fType == String.class) {
                    MemorySegment strSegment = arena.allocateFrom((String) val);
                    structSegment.set(ValueLayout.ADDRESS, offset, strSegment);
                } else if (fType == float[].class) {
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
                    });
                } else if (fType.isArray()) {
                    Object[] arr = (Object[]) val;
                    MemorySegment subSeg = arena.allocate(ValueLayout.ADDRESS, arr.length);
                    for (int j = 0; j < arr.length; j++) {
                        MemorySegment elementSeg = marshalCustomObject(arr[j], arena, copyBackTasks, seen, nativeToJava);
                        subSeg.setAtIndex(ValueLayout.ADDRESS, j, elementSeg);
                    }
                    structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                } else if (val instanceof Collection<?> col) {
                    if (!col.isEmpty()) {
                        Object first = col.iterator().next();
                        if (first instanceof Float) {
                            float[] arr = new float[col.size()];
                            int idx = 0;
                            for (Object x : col) arr[idx++] = (Float) x;
                            MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, arr);
                            structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                            copyBackTasks.add(() -> {
                                if (val instanceof java.util.List) {
                                    var l = (java.util.List<Float>) val;
                                    for (int k = 0; k < arr.length; k++)
                                        l.set(k, subSeg.getAtIndex(ValueLayout.JAVA_FLOAT, k));
                                }
                            });
                        } else if (first instanceof Integer) {
                            int[] arr = col.stream().mapToInt(x -> (Integer) x).toArray();
                            MemorySegment subSeg = arena.allocateFrom(ValueLayout.JAVA_INT, arr);
                            structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                            copyBackTasks.add(() -> {
                                if (val instanceof java.util.List) {
                                    var l = (java.util.List<Integer>) val;
                                    for (int k = 0; k < arr.length; k++)
                                        l.set(k, subSeg.getAtIndex(ValueLayout.JAVA_INT, k));
                                }
                            });
                        } else {
                            MemorySegment subSeg = arena.allocate(ValueLayout.ADDRESS, col.size());
                            int idx = 0;
                            for (Object item : col) {
                                MemorySegment elementSeg = marshalCustomObject(item, arena, copyBackTasks, seen, nativeToJava);
                                subSeg.setAtIndex(ValueLayout.ADDRESS, idx++, elementSeg);
                            }
                            structSegment.set(ValueLayout.ADDRESS, offset, subSeg);
                        }
                    } else {
                        structSegment.set(ValueLayout.ADDRESS, offset, MemorySegment.NULL);
                    }
                } else {
                    MemorySegment childSegment = marshalCustomObject(val, arena, copyBackTasks, seen, nativeToJava);
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
                        } else if (!fType.isPrimitive() && fType != String.class && !fType.isArray() && !java.util.Collection.class.isAssignableFrom(fType)) {
                            MemorySegment actualAddressSeg = structSegment.get(ValueLayout.ADDRESS, offset);
                            if (actualAddressSeg.address() == 0) {
                                field.set(obj, null);
                            } else {
                                Object linkedJavaObj = nativeToJava.get(actualAddressSeg.address());
                                if (linkedJavaObj == null) {
                                    linkedJavaObj = unmarshalCustomObject(actualAddressSeg, fType, nativeToJava);
                                }
                                field.set(obj, linkedJavaObj);
                            }
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