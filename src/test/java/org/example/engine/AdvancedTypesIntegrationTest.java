package org.example.engine;

import org.example.annotation.MiteStruct;
import org.example.compiler.CppCompiler;
import org.example.scanner.FunctionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdvancedTypesIntegrationTest {

    private static CppEngine engine;

    private static final Path CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "mite-adv-test-cache");

    @MiteStruct
    public static class TestProduct {
        private long id;
        private double price;
        private String name;

        public TestProduct() {
        }

        public TestProduct(long id, double price, String name) {
            this.id = id;
            this.price = price;
            this.name = name;
        }
    }

    @BeforeAll
    static void setUp(@TempDir Path scriptsDir) throws Exception {
        Files.createDirectories(CACHE_DIR);

        Path cppFile = scriptsDir.resolve("advanced_types.cpp");
        Files.writeString(cppFile, """
                #include <numeric>
                #include <vector>
                
                struct TestProduct {
                    long long id;
                    double price;
                    const char* name;
                };
                
                extern "C" {
                    // @mite
                    int sumList(int* arr, int size) {
                        std::vector<int> vec(arr, arr + size);
                        return std::accumulate(vec.begin(), vec.end(), 0);
                    }
                
                    // @mite
                    double getProductPrice(TestProduct* product) {
                        if (product == nullptr) return -1.0;
                        return product->price;
                    }
                }
                """);

        CppCompiler compiler = new CppCompiler(CACHE_DIR, null, List.of());
        FunctionRegistry registry = new FunctionRegistry(scriptsDir);
        engine = new DefaultCppEngine(compiler, registry);
    }

    @Test
    void testListToVectorMarshalling() {
        List<Integer> numbers = new java.util.LinkedList<>(List.of(10, 20, 30, 40));

        int sum = (int) engine.execute("sumList", numbers, numbers.size());
        assertEquals(100, sum, "C++ should correctly sum the list elements");
    }

    @Test
    void testSetToVectorMarshalling() {
        Set<Integer> numbers = Set.of(5, 15, 50);

        int sum = (int) engine.execute("sumList", numbers, numbers.size());
        assertEquals(70, sum, "C++ should correctly sum the set elements");
    }

    @Test
    void testCustomObjectToStructMarshalling() {
        TestProduct product = new TestProduct(101L, 99.99, "SuperGadget");

        double price = (double) engine.execute("getProductPrice", product);
        assertEquals(99.99, price, 0.001, "C++ should correctly read the double field from mapped struct");
    }

    @Test
    void testNullObjectMarshalling() {
        double price = (double) engine.execute("getProductPrice", (Object) null);
        assertEquals(-1.0, price, 0.001, "C++ should receive nullptr and return -1.0");
    }
}