package org.example.scanner;

import org.example.parser.CppParser;
import org.example.parser.FunctionSignature;
import org.example.engine.NativeResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FunctionRegistry {

    private final Path scriptsDir;
    private final CppParser parser = new CppParser();
    private final Map<String, List<ResolvedFunction>> index = new ConcurrentHashMap<>();

    public FunctionRegistry(Path scriptsDir) {
        this.scriptsDir = scriptsDir;
        scan();
        startWatcher();
    }

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

    private void indexFile(Path cppFile) {
        List<FunctionSignature> sigs = parser.parse(cppFile);
        for (FunctionSignature sig : sigs) {
            index.computeIfAbsent(sig.name(), k -> new ArrayList<>())
                    .add(new ResolvedFunction(sig, cppFile));
        }
    }

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
                    System.out.println("[MITE] Change in cppScripts — rescan...");
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

    public Optional<ResolvedFunction> resolve(String name, Object[] args) {
        List<ResolvedFunction> overloads = index.get(name);
        if (overloads == null) return Optional.empty();

        return overloads.stream()
                .filter(f -> matches(f.signature(), args))
                .findFirst();
    }

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

    private boolean typeMatches(String cppType, Object arg) {
        if (arg instanceof NativeResource) {
            return cppType.endsWith("*");
        }

        return switch (cppType) {
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

                    Class<?> argClass = arg.getClass();
                    String javaClassName = argClass.getSimpleName();

                    if (javaClassName.equals(baseCppType)) {
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

    public record ResolvedFunction(FunctionSignature signature, Path file) {
    }
}