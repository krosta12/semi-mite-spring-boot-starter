package org.example.scanner;

import org.example.parser.CppParser;
import org.example.parser.FunctionSignature;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;


public class FunctionRegistry {

    private final Path scriptsDir;
    private final CppParser parser = new CppParser();

    private final Map<String, List<ResolvedFunction>> index = new HashMap<>();

    public FunctionRegistry(Path scriptsDir) {
        this.scriptsDir = scriptsDir;
        scan();
    }

    private void scan() {
        try (var stream = Files.walk(scriptsDir)) {
            stream.filter(p -> p.toString().endsWith(".cpp"))
                    .forEach(this::indexFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan directory: " + scriptsDir, e);
        }
    }

    private void indexFile(Path cppFile) {
        List<FunctionSignature> sigs = parser.parse(cppFile);
        for (FunctionSignature sig : sigs) {
            index.computeIfAbsent(sig.name(), k -> new ArrayList<>())
                    .add(new ResolvedFunction(sig, cppFile));
        }
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
            default            -> false;
        };
    }

    public record ResolvedFunction(FunctionSignature signature, Path file) {}
}