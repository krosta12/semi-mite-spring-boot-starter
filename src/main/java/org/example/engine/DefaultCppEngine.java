package org.example.engine;

import org.example.compiler.CppCompiler;
import org.example.compiler.MiteException;
import org.example.parser.FunctionSignature;
import org.example.scanner.FunctionRegistry;
import org.example.scanner.FunctionRegistry.ResolvedFunction;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class DefaultCppEngine implements CppEngine {

    private final CppCompiler compiler;
    private final FunctionRegistry registry;
    private final Linker linker = Linker.nativeLinker();

    private final Path cacheFile = Path.of("cppScripts", ".mite_cache.properties");
    private final Properties cache = new Properties();

    public DefaultCppEngine(CppCompiler compiler, FunctionRegistry registry) {
        this.compiler = compiler;
        this.registry = registry;
        loadCache();
    }

    @Override
    public Object execute(String functionName, Object... args) {
        ResolvedFunction resolved = registry.resolve(functionName, args)
                .orElseThrow(() -> {
                    String argTypes = Arrays.stream(args)
                            .map(a -> a == null ? "null" : a.getClass().getSimpleName())
                            .collect(java.util.stream.Collectors.joining(", "));
                    return new MiteException(
                            "Function '" + functionName + "(" + argTypes + ")' not found. " +
                                    "Check // @mite annotation and argument types in cppScripts."
                    );
                });

        Path lib = getOrCompile(resolved.file());
        return invoke(lib, resolved.signature(), args);
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
        SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());

        MemorySegment fn = lookup.find(sig.name())
                .orElseThrow(() -> new MiteException(
                        "Symbol '" + sig.name() + "' not found. Add extern \"C\" before the function."
                ));

        FunctionDescriptor descriptor = buildDescriptor(sig);
        MethodHandle handle = linker.downcallHandle(fn, descriptor);

        try (Arena arena = Arena.ofConfined()) {
            Object[] nativeArgs = marshalArgs(args, sig.paramTypes(), arena);
            if ("void".equals(sig.returnType())) {
                handle.invokeWithArguments(nativeArgs);
                return null;
            }
            Object result = handle.invokeWithArguments(nativeArgs);
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
        return switch (cppType) {
            case "int8_t", "uint8_t", "char", "unsigned char" -> ValueLayout.JAVA_BYTE;
            case "int16_t", "uint16_t", "short", "unsigned short" -> ValueLayout.JAVA_SHORT;
            case "int", "unsigned int", "int32_t", "uint32_t" -> ValueLayout.JAVA_INT;
            case "long", "long long", "unsigned long long", "int64_t", "uint64_t" -> ValueLayout.JAVA_LONG;
            case "float" -> ValueLayout.JAVA_FLOAT;
            case "double" -> ValueLayout.JAVA_DOUBLE;
            case "bool" -> ValueLayout.JAVA_BOOLEAN;
            case "std::string", "const char*" -> ValueLayout.ADDRESS;
            case "void" -> null;
            default -> throw new MiteException("Unknown type: " + cppType);
        };
    }

    private Object[] marshalArgs(Object[] args, List<String> paramTypes, Arena arena) {
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            String type = paramTypes.get(i);
            if ("std::string".equals(type) || "const char*".equals(type)) {
                result[i] = arena.allocateFrom((String) args[i]);
            } else {
                result[i] = args[i];
            }
        }
        return result;
    }

    private Object unmarshalResult(Object result, String returnType) {
        if (result instanceof MemorySegment seg &&
                ("const char*".equals(returnType) || "std::string".equals(returnType))) {
            MemorySegment safe = seg.reinterpret(4096);
            long len = 0;
            while (len < 4096 && safe.get(ValueLayout.JAVA_BYTE, len) != 0) len++;
            return new String(safe.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE));
        }
        return result;
    }
}