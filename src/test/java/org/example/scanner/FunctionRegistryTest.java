package org.example.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FunctionRegistryTest {

    @Test
    void findsFunction(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("test.cpp"), """
                // @mite
                int add(int a, int b)
                {
                    return a + b;
                }
                """);

        FunctionRegistry registry = new FunctionRegistry(dir);
        Optional<FunctionRegistry.ResolvedFunction> result = registry.resolve("add", new Object[]{1, 2});

        assertTrue(result.isPresent());
        assertEquals("add", result.get().signature().name());
    }

    @Test
    void returnsEmptyForUnknownFunction(@TempDir Path dir) {
        FunctionRegistry registry = new FunctionRegistry(dir);
        Optional<FunctionRegistry.ResolvedFunction> result = registry.resolve("unknown", new Object[]{});

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForWrongArgTypes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("test.cpp"), """
                // @mite
                int add(int a, int b)
                {
                    return a + b;
                }
                """);

        FunctionRegistry registry = new FunctionRegistry(dir);
        Optional<FunctionRegistry.ResolvedFunction> result = registry.resolve("add", new Object[]{1.0, 2.0});

        assertTrue(result.isEmpty());
    }

    @Test
    void resolvesOverloadedFunction(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("test.cpp"), """
                // @mite
                int add(int a, int b)
                {
                    return a + b;
                }
                
                // @mite
                double add(double a, double b)
                {
                    return a + b;
                }
                """);

        FunctionRegistry registry = new FunctionRegistry(dir);

        Optional<FunctionRegistry.ResolvedFunction> intVersion = registry.resolve("add", new Object[]{1, 2});
        Optional<FunctionRegistry.ResolvedFunction> doubleVersion = registry.resolve("add", new Object[]{1.0, 2.0});

        assertTrue(intVersion.isPresent());
        assertTrue(doubleVersion.isPresent());
        assertEquals("int", intVersion.get().signature().returnType());
        assertEquals("double", doubleVersion.get().signature().returnType());
    }

    @Test
    void scansMultipleFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("math.cpp"), """
                // @mite
                int add(int a, int b)
                {
                    return a + b;
                }
                """);
        Files.writeString(dir.resolve("strings.cpp"), """
                // @mite
                const char* greet(const char* name)
                {
                    return name;
                }
                """);

        FunctionRegistry registry = new FunctionRegistry(dir);

        assertTrue(registry.resolve("add", new Object[]{1, 2}).isPresent());
        assertTrue(registry.resolve("greet", new Object[]{"test"}).isPresent());
    }
}