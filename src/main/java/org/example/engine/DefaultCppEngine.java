package org.example.engine;

import org.example.compiler.CppCompiler;
import org.example.compiler.MiteException;
import org.example.parser.FunctionSignature;
import org.example.scanner.FunctionRegistry;
import org.example.scanner.FunctionRegistry.ResolvedFunction;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;

public class DefaultCppEngine implements CppEngine {

    private final CppCompiler compiler;
    private final FunctionRegistry registry;
    private final Linker linker = Linker.nativeLinker();

    public DefaultCppEngine(CppCompiler compiler, FunctionRegistry registry) {
        this.compiler = compiler;
        this.registry = registry;
    }

    @Override
    public Object execute(String functionName, Object... args) {
        ResolvedFunction resolved = registry.resolve(functionName, args)
                .orElseThrow(() -> new MiteException(
                        "Function '" + functionName + "' with such argument types not found. " +
                                "Check that the function is marked with // @mite in cppScripts."
                ));

        Path lib = compiler.compile(resolved.file());

        return invoke(lib, resolved.signature(), args);
    }

    private Object invoke(Path lib, FunctionSignature sig, Object[] args) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());

        MemorySegment fn = lookup.find(sig.name())
                .orElseThrow(() -> new MiteException(
                        "Symbol '" + sig.name() + "' not found in the compiled library. " +
                                "Add extern \"C\" before the function."
                ));

        FunctionDescriptor descriptor = buildDescriptor(sig);
        MethodHandle handle = linker.downcallHandle(fn, descriptor);

        try (Arena arena = Arena.ofConfined()) {
            Object[] nativeArgs = marshalArgs(args, sig.paramTypes(), arena);
            Object result = handle.invokeWithArguments(nativeArgs);
            return unmarshalResult(result, sig.returnType());
        } catch (Throwable e) {
            throw new MiteException("Error calling function '" + sig.name() + "': " + e.getMessage(), e);
        }
    }

    private FunctionDescriptor buildDescriptor(FunctionSignature sig) {
        MemoryLayout returnLayout = toLayout(sig.returnType());
        List<MemoryLayout> paramLayouts = sig.paramTypes().stream()
                .map(this::toLayout)
                .toList();

        if (returnLayout == null) {
            // void
            return FunctionDescriptor.ofVoid(paramLayouts.toArray(new MemoryLayout[0]));
        }
        return FunctionDescriptor.of(returnLayout, paramLayouts.toArray(new MemoryLayout[0]));
    }

    private MemoryLayout toLayout(String cppType) {
        return switch (cppType) {
            case "int"         -> ValueLayout.JAVA_INT;
            case "long"        -> ValueLayout.JAVA_LONG;
            case "double"      -> ValueLayout.JAVA_DOUBLE;
            case "float"       -> ValueLayout.JAVA_FLOAT;
            case "bool"        -> ValueLayout.JAVA_BOOLEAN;
            case "std::string" -> ValueLayout.ADDRESS;
            case "void"        -> null;
            default -> throw new MiteException("Unknown type: " + cppType);
        };
    }

    private Object[] marshalArgs(Object[] args, List<String> paramTypes, Arena arena) {
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if ("std::string".equals(paramTypes.get(i))) {
                String s = (String) args[i];
                result[i] = arena.allocateFrom(s);
            } else {
                result[i] = args[i];
            }
        }
        return result;
    }

    private Object unmarshalResult(Object result, String returnType) {
        return result;
    }
}