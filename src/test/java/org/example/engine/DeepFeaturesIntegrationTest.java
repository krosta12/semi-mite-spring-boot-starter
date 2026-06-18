package org.example.engine;

import org.example.annotation.MiteStruct;
import org.example.compiler.CppCompiler;
import org.example.compiler.MiteException;
import org.example.scanner.FunctionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepFeaturesIntegrationTest {

    private static CppEngine engine;
    private static final Path CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "mite-deep-test-cache");


    @MiteStruct
    public static class BiNode {
        private int id;
        private BiNode next;

        public BiNode() {}
        public BiNode(int id) { this.id = id; }

        public int getId() { return id; }
        public BiNode getNext() { return next; }
        public void setNext(BiNode next) { this.next = next; }
    }

    @MiteStruct
    public static class Manager {
        private int age;
        private double bonus;

        public Manager() {}
        public Manager(int age, double bonus) {
            this.age = age;
            this.bonus = bonus;
        }
    }

    @MiteStruct
    public static class Company {
        private String name;
        private Manager manager;

        public Company() {}
        public Company(String name, Manager manager) {
            this.name = name;
            this.manager = manager;
        }
    }



    @BeforeAll
    static void setUp(@TempDir Path scriptsDir) throws Exception {
        Files.createDirectories(CACHE_DIR);

        Path cppFile = scriptsDir.resolve("deep_features.cpp");
        Files.writeString(cppFile, """
                #include <cstring>
                
                typedef bool boolean;
                
                struct BiNode {
                    int id;
                    BiNode* next;
                };
                
                struct Manager {
                    int age;
                    double bonus;
                };
                
                struct Company {
                    const char* name;
                    Manager* manager;
                };
                
                extern "C" {
                    // @mite
                    void modifyCyclicGraph(BiNode* node) {
                        if (node != nullptr) {
                            node->id += 100;
                            if (node->next != nullptr) {
                                node->next->id += 200;
                            }
                        }
                    }
                
                    // @mite
                    BiNode* returnPassthroughNode(BiNode* node) {
                        return node;
                    }
                
                    // @mite
                    void mutateArrays(int* ints, boolean* bools, int size) {
                        for (int i = 0; i < size; i++) {
                            ints[i] = ints[i] * 10;
                            bools[i] = !bools[i];
                        }
                    }
                
                    // @mite
                    void promoteManager(Company* company) {
                        if (company != nullptr && company->manager != nullptr) {
                            company->manager->age += 1;
                            company->manager->bonus *= 1.5;
                        }
                    }
                }
                """);

        CppCompiler compiler = new CppCompiler(CACHE_DIR, null, List.of());
        FunctionRegistry registry = new FunctionRegistry(scriptsDir);
        engine = new DefaultCppEngine(compiler, registry);
    }



    @Test
    void testCircularGraphReferencesAndCopyBack() {
        BiNode node1 = new BiNode(1);
        BiNode node2 = new BiNode(2);
        node1.setNext(node2);
        node2.setNext(node1);

        engine.execute("modifyCyclicGraph", node1);

        assertEquals(101, node1.getId(), "Node1 ID should be incremented by C++");
        assertEquals(202, node2.getId(), "Node2 ID should be incremented by C++");

        assertSame(node2, node1.getNext());
        assertSame(node1, node2.getNext());
    }

    @Test
    void testReturnCustomStructPointerFromCpp() {
        BiNode sourceNode = new BiNode(42);

        Object result = engine.execute("returnPassthroughNode", sourceNode);

        assertNotNull(result);
        assertTrue(result instanceof BiNode);
        BiNode returnedNode = (BiNode) result;

        assertEquals(42, returnedNode.getId());
        assertSame(sourceNode, returnedNode, "Engine should return the EXACT same Java instance due to pointer tracking!");
    }

    @Test
    void testPrimitiveAndBooleanArrayMutationCopyBack() {
        int[] originalInts = {1, 2, 3};
        boolean[] originalBools = {true, false, true};

        engine.execute("mutateArrays", originalInts, originalBools, originalInts.length);

        assertArrayEquals(new int[]{10, 20, 30}, originalInts, "Int array must be updated after C++ execution");

        assertFalse(originalBools[0], "First boolean should become false");
        assertTrue(originalBools[1], "Second boolean should become true");
        assertFalse(originalBools[2], "Third boolean should become false");
    }

    @Test
    void testNestedStructuresAndStrings() {
        Manager manager = new Manager(35, 5000.0);
        Company company = new Company("MiteCorp", manager);

        engine.execute("promoteManager", company);

        assertEquals(36, company.manager.age, "Manager age should be incremented");
        assertEquals(7500.0, company.manager.bonus, 0.001, "Manager bonus should be multiplied by 1.5");
        assertEquals("MiteCorp", company.name, "String field must remain intact");
    }
}