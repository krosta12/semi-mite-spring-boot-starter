package org.example.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CppParserTest {

    private final CppParser parser = new CppParser();

    @Test
    void parsesIntFunction(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                int add(int a, int b)
                {
                    return a + b;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);

        assertEquals(1, sigs.size());
        assertEquals("add", sigs.get(0).name());
        assertEquals("int", sigs.get(0).returnType());
        assertEquals(List.of("int", "int"), sigs.get(0).paramTypes());
    }

    @Test
    void parsesDoubleFunction(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                double multiply(double a, double b)
                {
                    return a * b;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);

        assertEquals(1, sigs.size());
        assertEquals("multiply", sigs.get(0).name());
        assertEquals("double", sigs.get(0).returnType());
        assertEquals(List.of("double", "double"), sigs.get(0).paramTypes());
    }

    @Test
    void parsesConstCharStarFunction(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                const char* greet(const char* name)
                {
                    return name;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);

        assertEquals(1, sigs.size());
        assertEquals("greet", sigs.get(0).name());
        assertEquals("const char*", sigs.get(0).returnType());
        assertEquals(List.of("const char*"), sigs.get(0).paramTypes());
    }

    @Test
    void parsesBoolFunction(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                bool isPositive(int n)
                {
                    return n > 0;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);

        assertEquals(1, sigs.size());
        assertEquals("bool", sigs.get(0).returnType());
        assertEquals(List.of("int"), sigs.get(0).paramTypes());
    }

    @Test
    void parsesMultipleFunctions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                int add(int a, int b)
                {
                    return a + b;
                }
                
                // @mite
                double multiply(double a, double b)
                {
                    return a * b;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);
        assertEquals(2, sigs.size());
    }

    @Test
    void ignoresFunctionsWithoutAnnotation(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                int add(int a, int b)
                {
                    return a + b;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);
        assertTrue(sigs.isEmpty());
    }

    @Test
    void ignoresFunctionsWithUnsupportedTypes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                void* process(void* data)
                {
                    return data;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);
        assertTrue(sigs.isEmpty());
    }

    @Test
    void parsesOverloadedFunctions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
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

        List<FunctionSignature> sigs = parser.parse(file);
        assertEquals(2, sigs.size());
        assertEquals("add", sigs.get(0).name());
        assertEquals("add", sigs.get(1).name());
        assertNotEquals(sigs.get(0).returnType(), sigs.get(1).returnType());
    }

    @Test
    void parsesPrimitivePointers(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                int sumArray(int* arr, int size)
                {
                    return 0;
                }
                
                // @mite
                double processDoubles(double * arr, int size)
                {
                    return 0.0;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);

        assertEquals(2, sigs.size());
        assertEquals(List.of("int*", "int"), sigs.get(0).paramTypes());

        assertEquals(List.of("double*", "int"), sigs.get(1).paramTypes());
    }

    @Test
    void parsesCustomObjectPointers(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.cpp");
        Files.writeString(file, """
                // @mite
                void printUser(User* user)
                {
                }
                
                // @mite
                Order* createOrder(Order* order)
                {
                    return order;
                }
                """);

        List<FunctionSignature> sigs = parser.parse(file);

        assertEquals(2, sigs.size());
        assertEquals("User*", sigs.get(0).paramTypes().get(0));
        assertEquals("Order*", sigs.get(1).returnType());
        assertEquals("Order*", sigs.get(1).paramTypes().get(0));
    }
}