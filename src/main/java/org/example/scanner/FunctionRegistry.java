package org.example.scanner;

import org.example.parser.CppParser;
import org.example.parser.FunctionSignature;

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
            stream.filter(p -> p.toString().endsWith(".cpp"))
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
            if (!typeMatches(paramTypes.get(i), args[i])) return false;
        }
        return true;
    }

    private boolean typeMatches(String cppType, Object arg) {
        return switch (cppType) {
            case "int"         -> arg instanceof Integer;
            case "long"        -> arg instanceof Long;
            case "double"      -> arg instanceof Double;
            case "float"       -> arg instanceof Float;
            case "bool"        -> arg instanceof Boolean;
            case "std::string" -> arg instanceof String;
            case "const char*" -> arg instanceof String;
            default            -> false;
        };
    }

    public record ResolvedFunction(FunctionSignature signature, Path file) {}
}