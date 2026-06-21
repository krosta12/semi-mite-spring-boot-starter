package org.example.scanner;

import org.example.parser.CppParser;
import org.example.parser.FunctionSignature;
import org.example.engine.NativeResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains a live index of native C++ functions available for invocation,
 * discovered by scanning {@code .cpp} files in the configured scripts directory.
 *
 * <p>On construction, the registry performs an initial scan of {@code scriptsDir}
 * and registers all functions marked with {@code // @mite}. A background daemon
 * thread then monitors the directory for file changes and automatically rescans
 * when a {@code .cpp} file is created, modified, or deleted — enabling runtime
 * hot-reload of native code without restarting the application.
 *
 * <h2>Indexing</h2>
 * <p>Functions are indexed by name. Multiple overloads (functions with the same name
 * but different parameter signatures) are stored as a list under the same key.
 * During resolution, overloads are matched by comparing argument types at runtime.
 *
 * <h2>Function name uniqueness</h2>
 * <p>All {@code .cpp} files in {@code scriptsDir} share a single flat namespace.
 * If two files define a {@code // @mite}-marked function with the same name and
 * compatible parameter types, the first match found during {@link #resolve} is used.
 * To avoid ambiguity, function names should be unique across all source files.
 *
 * <h2>Hot reload</h2>
 * <p>The background watcher uses a 150ms debounce delay ({@code Thread.sleep(150)})
 * before rescanning, to avoid reading partially written files when IDEs save in
 * multiple stages. The watcher thread is daemon, so it does not prevent JVM shutdown.
 *
 * @see org.example.parser.CppParser
 * @see FunctionSignature
 * @see org.example.engine.DefaultCppEngine
 */
public class FunctionRegistry {

    private final Path scriptsDir;
    private final CppParser parser = new CppParser();

    /**
     * The function index: maps function name to a list of all registered overloads,
     * each paired with the source file they were parsed from.
     */
    private final Map<String, List<ResolvedFunction>> index = new ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(FunctionRegistry.class);

    public FunctionRegistry(Path scriptsDir) {
        this.scriptsDir = scriptsDir;
        scan();
        startWatcher();
    }

    /**
     * Clears the current index and rescans all {@code .cpp} files in {@code scriptsDir}.
     *
     * <p>Files under the {@code compileCache} subdirectory are excluded from scanning
     * to avoid indexing preprocessed copies of source files stored there by
     * {@link org.example.compiler.CppCompiler}.
     *
     * <p>If {@code scriptsDir} does not exist, the scan completes immediately with
     * an empty index.
     *
     * @throws RuntimeException if the directory cannot be traversed
     */
    private void scan() {
        index.clear();
        if (!Files.exists(scriptsDir)) return;
        try (var stream = Files.walk(scriptsDir)) {
            stream
                    .filter(p -> !p.startsWith(scriptsDir.resolve("compileCache")))
                    .filter(p -> p.toString().endsWith(".cpp"))
                    .forEach(this::indexFile);
        } catch (IOException e) {
            throw new RuntimeException("Could not scan directory: " + scriptsDir, e);
        }
    }

    /**
     * Parses a single {@code .cpp} file and adds all discovered {@code // @mite}
     * functions to the index.
     *
     * @param cppFile the source file to parse and index
     */
    private void indexFile(Path cppFile) {
        List<FunctionSignature> sigs = parser.parse(cppFile);
        for (FunctionSignature sig : sigs) {
            index.computeIfAbsent(sig.name(), k -> new ArrayList<>())
                    .add(new ResolvedFunction(sig, cppFile));
        }
    }

    /**
     * Starts a background daemon thread that monitors {@code scriptsDir} for
     * file system changes and triggers a full rescan when any change is detected.
     *
     * <p>A 150ms debounce sleep is applied after detecting the first event before
     * draining the event queue and rescanning, to allow IDEs that write files in
     * multiple stages to finish writing before the parser reads the file.
     *
     * <p>The watcher thread is daemon so it does not prevent JVM shutdown.
     */
    private void startWatcher() {
        Thread watcher = new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                scriptsDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                while (true) {
                    WatchKey key = watchService.take();
                    Thread.sleep(150);
                    key.pollEvents();
                    log.debug("[MITE] Change in cppScripts — rescan...");
                    scan();
                    key.reset();
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * Resolves a native function by name and runtime argument types.
     *
     * <p>All registered overloads for {@code name} are tested against {@code args}
     * using {@link #matches}. The first matching overload is returned. If no overload
     * matches, {@link Optional#empty()} is returned and the caller is expected to
     * throw a descriptive error.
     *
     * @param name the native function name to look up
     * @param args the Java arguments that will be passed to the function;
     *             their runtime types are used for overload matching
     * @return the first {@link ResolvedFunction} whose signature matches {@code args},
     * or {@link Optional#empty()} if no match is found
     */
    public Optional<ResolvedFunction> resolve(String name, Object[] args) {
        List<ResolvedFunction> overloads = index.get(name);
        if (overloads == null) return Optional.empty();

        return overloads.stream()
                .filter(f -> matches(f.signature(), args))
                .findFirst();
    }

    /**
     * Checks whether the given signature is compatible with the provided arguments.
     *
     * <p>The parameter count must match exactly. Each argument is checked against
     * its corresponding C++ parameter type using {@link #typeMatches}.
     * A {@code null} argument is accepted for any pointer or string type.
     *
     * @param sig  the candidate native function signature
     * @param args the Java arguments to match against
     * @return {@code true} if all parameter types match the corresponding argument types
     */
    private boolean matches(FunctionSignature sig, Object[] args) {
        List<String> paramTypes = sig.paramTypes();
        if (paramTypes.size() != args.length) return false;

        for (int i = 0; i < args.length; i++) {
            String paramType = paramTypes.get(i);
            Object arg = args[i];

            if (arg == null) {
                if (!paramType.endsWith("*") && !paramType.equals("const char*")) {
                    return false;
                }
                continue;
            }

            if (!typeMatches(paramType, arg)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Determines whether a single Java argument is compatible with a C++ parameter type.
     *
     * <p>Matching rules:
     * <ul>
     *   <li>{@link NativeResource} matches any pointer type ({@code T*}).</li>
     *   <li>Primitive arrays ({@code float[]}, {@code int[]}, etc.) match their
     *       corresponding C++ pointer types ({@code float*}, {@code int*}, etc.).</li>
     *   <li>{@link java.util.Collection} matches any numeric pointer type.</li>
     *   <li>Scalar Java types match their exact C++ equivalents
     *       ({@code Integer} → {@code int}, {@code Float} → {@code float}, etc.).</li>
     *   <li>For custom struct pointer types (e.g., {@code User*}), the Java class
     *       simple name is compared to the base C++ type. Arrays and collections of
     *       custom structs also match. {@code struct} and {@code class} keywords
     *       in the C++ type string are stripped before comparison.</li>
     * </ul>
     *
     * @param cppType the C++ type string from the parsed function signature
     * @param arg     the Java argument whose type is being checked
     * @return {@code true} if the argument is compatible with the C++ type
     */
    private boolean typeMatches(String cppType, Object arg) {
        if (arg instanceof NativeResource) {
            return cppType.endsWith("*");
        }

        return switch (cppType) {
            case "bool*" -> arg instanceof boolean[];
            case "int*", "int32_t*" -> arg instanceof java.util.Collection || arg instanceof int[];
            case "long long*", "int64_t*" -> arg instanceof java.util.Collection || arg instanceof long[];
            case "float*" -> arg instanceof java.util.Collection || arg instanceof float[];
            case "double*" -> arg instanceof java.util.Collection || arg instanceof double[];

            case "int8_t", "uint8_t", "char", "unsigned char" -> arg instanceof Byte;
            case "int16_t", "uint16_t", "short", "unsigned short" -> arg instanceof Short;
            case "int", "unsigned int", "int32_t", "uint32_t" -> arg instanceof Integer;
            case "long", "long long", "unsigned long long", "int64_t", "uint64_t" -> arg instanceof Long;

            case "float" -> arg instanceof Float;
            case "double" -> arg instanceof Double;
            case "bool" -> arg instanceof Boolean;
            case "std::string", "const char*" -> arg instanceof String;

            default -> {
                if (cppType.endsWith("*")) {
                    String baseCppType = cppType.replace("*", "").trim();

                    if (baseCppType.startsWith("struct ")) baseCppType = baseCppType.substring(7).trim();
                    if (baseCppType.startsWith("class ")) baseCppType = baseCppType.substring(6).trim();

                    Class<?> argClass = arg.getClass();
                    String javaClassName = argClass.getSimpleName();
                    String javaFullClassName = argClass.getName();

                    if (javaClassName.equals(baseCppType) || javaFullClassName.equals(baseCppType)) {
                        yield true;
                    }

                    if (argClass.isArray()) {
                        String componentName = argClass.getComponentType().getSimpleName();
                        if (componentName.equals(baseCppType)) {
                            yield true;
                        }
                    }

                    if (arg instanceof java.util.Collection) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    /**
     * A resolved match pairing a parsed {@link FunctionSignature} with the
     * {@code .cpp} source file that declared it.
     *
     * <p>Used by {@link org.example.engine.DefaultCppEngine} to determine which
     * file to compile when invoking the function.
     *
     * @param signature the parsed function signature
     * @param file      path to the {@code .cpp} source file containing the function
     */
    public record ResolvedFunction(FunctionSignature signature, Path file) {
    }
}