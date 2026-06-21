package org.example.parser;

import java.util.List;

/**
 * Immutable value object representing a parsed C++ function signature.
 *
 * <p>Instances are produced by {@link CppParser} when it encounters a
 * {@code // @mite}-marked function in a {@code .cpp} source file. They are
 * stored in the {@link org.example.scanner.FunctionRegistry} and used by
 * {@link org.example.engine.DefaultCppEngine} to build the Panama
 * {@link java.lang.foreign.FunctionDescriptor} required for downcall linking.
 *
 * <p>Example — given this C++ function:
 * <pre>{@code
 * // @mite
 * float calculate_cosine_similarity(const float* vecA, const float* vecB, int length) {
 * }</pre>
 * the resulting {@code FunctionSignature} would be:
 * <pre>{@code
 * new FunctionSignature(
 *     "calculate_cosine_similarity",
 *     "float",
 *     List.of("float*", "float*", "int")
 * )
 * }</pre>
 *
 * <p>Type strings in {@code paramTypes} and {@code returnType} use the C++ type
 * naming convention as parsed from the source (e.g., {@code "float*"}, {@code "int32_t"},
 * {@code "const char*"}, {@code "void"}).
 *
 * @param name       the native function name as it appears in the C++ source
 * @param returnType the C++ return type string; {@code "void"} for functions with no return value
 * @param paramTypes ordered list of C++ parameter type strings; empty for zero-parameter functions
 *
 * @see CppParser
 * @see org.example.scanner.FunctionRegistry
 */
public record FunctionSignature(
        String name,
        String returnType,
        List<String> paramTypes
) {
}