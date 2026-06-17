package org.example.engine;

import org.example.compiler.CppCompiler;
import org.example.compiler.MiteException;
import org.example.scanner.FunctionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CppEngineIntegrationTest {

    private static final Path CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "mite-test-cache");

    private CppEngine engine;

    @BeforeEach
    void setUp() throws IOException, URISyntaxException {
        Files.createDirectories(CACHE_DIR);

        Path scriptsDir = Path.of(
                getClass().getClassLoader().getResource("cppScripts").toURI()
        );

        CppCompiler compiler = new CppCompiler(CACHE_DIR, null);
        FunctionRegistry registry = new FunctionRegistry(scriptsDir);
        engine = new DefaultCppEngine(compiler, registry);
    }

    // --- Base types ---

    @Test
    void addIntegers() {
        assertEquals(30, engine.execute("add", 10, 20));
    }

    @Test
    void addNegativeIntegers() {
        assertEquals(-5, engine.execute("add", -10, 5));
    }

    @Test
    void addZero() {
        assertEquals(0, engine.execute("add", 0, 0));
    }

    @Test
    void addMaxInt() {
        assertEquals(Integer.MAX_VALUE, engine.execute("add", Integer.MAX_VALUE, 0));
    }

    @Test
    void multiplyDoubles() {
        assertEquals(13.5, engine.execute("multiply", 3.0, 4.5));
    }

    @Test
    void multiplyByZero() {
        assertEquals(0.0, engine.execute("multiply", 99.9, 0.0));
    }

    @Test
    void multiplyNegative() {
        assertEquals(-6.0, engine.execute("multiply", -2.0, 3.0));
    }

    @Test
    void isPositiveTrue() {
        assertEquals(true, engine.execute("isPositive", 5));
    }

    @Test
    void isPositiveFalse() {
        assertEquals(false, engine.execute("isPositive", -1));
    }

    @Test
    void isPositiveZero() {
        assertEquals(false, engine.execute("isPositive", 0));
    }

    @Test
    void greetString() {
        assertEquals("Hello, World!", engine.execute("greet", "World"));
    }

    @Test
    void greetEmptyName() {
        assertEquals("Hello, !", engine.execute("greet", ""));
    }

    @Test
    void greetLongName() {
        String longName = "A".repeat(500);
        String result = (String) engine.execute("greet", longName);
        assertTrue(result.startsWith("Hello, "));
        assertTrue(result.endsWith("!"));
    }

    // --- Function calls another function ---

    @Test
    void sumOfSquaresCallsHelper() {
        // square() is "private" without @mite and used inside
        assertEquals(25, engine.execute("sumOfSquares", 3, 4)); // 9 + 16
    }

    @Test
    void sumOfSquaresWithZero() {
        assertEquals(9, engine.execute("sumOfSquares", 3, 0));
    }

    // --- Function uses stdlib ---

    @Test
    void hypotenuseUsingStdlib() {
        assertEquals(5.0, (double) engine.execute("hypotenuse", 3.0, 4.0), 0.0001);
    }

    @Test
    void hypotenuseWithZero() {
        assertEquals(3.0, (double) engine.execute("hypotenuse", 3.0, 0.0), 0.0001);
    }

    // --- Recursion ---

    @Test
    void factorialBase() {
        assertEquals(1, engine.execute("factorial", 1));
    }

    @Test
    void factorialSmall() {
        assertEquals(120, engine.execute("factorial", 5));
    }

    @Test
    void factorialZero() {
        assertEquals(1, engine.execute("factorial", 0));
    }

    // --- Strings ---

    @Test
    void emptyStringPassthrough() {
        assertEquals("", engine.execute("emptyString", ""));
    }

    @Test
    void stringPassthrough() {
        assertEquals("hello", engine.execute("emptyString", "hello"));
    }

    // --- Errors ---

    @Test
    void throwsOnUnknownFunction() {
        assertThrows(MiteException.class, () -> engine.execute("unknown", 1, 2));
    }

    @Test
    void throwsOnWrongArgTypes() {
        assertThrows(MiteException.class, () -> engine.execute("add", 1.0, 2.0));
    }

    @Test
    void throwsOnWrongArgCount() {
        assertThrows(MiteException.class, () -> engine.execute("add", 1));
    }

    // --- Cache ---

    @Test
    void cacheWorksOnSecondCall() {
        Object first = engine.execute("add", 5, 5);
        Object second = engine.execute("add", 5, 5);
        assertEquals(first, second);
    }

    // --- Helper function without @mite is not exposed ---

    @Test
    void helperFunctionNotExposed() {
        assertThrows(MiteException.class, () -> engine.execute("square", 5));
    }


    @Test
    void voidFunctionReturnsNull() {
        assertNull(engine.execute("noReturn"));
    }


}