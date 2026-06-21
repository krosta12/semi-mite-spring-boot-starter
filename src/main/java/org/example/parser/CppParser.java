package org.example.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parses C++ source files and extracts {@link FunctionSignature}s from functions
 * marked with the {@code // @mite} annotation comment.
 *
 * <p>This parser operates directly on {@code .cpp} source text without requiring
 * a C++ compiler or header files. Functions are discovered by scanning for the
 * marker comment, then extracting the function signature from the following line(s).
 *
 * <p>The marker format is flexible — both {@code // @mite} (with a space) and
 * {@code //@mite} (without) are recognised. Multi-line signatures are supported:
 * if the line immediately after {@code // @mite} does not contain {@code {}
 * or {@code ;}, subsequent lines are collected until one does.
 *
 * <p>Recognised return types and parameter types are restricted to the set defined
 * in {@link #SUPPORTED_TYPES}, plus any pointer type (types ending with {@code *}).
 * Functions with unsupported or unrecognised types are silently skipped.
 *
 * <p>{@code extern "C"} declarations are transparently stripped before parsing,
 * so both of these forms are handled identically:
 * <pre>{@code
 * // @mite
 * float compute(float* data, int length) {
 *
 * // @mite
 * extern "C" float compute(float* data, int length) {
 * }</pre>
 *
 * @see FunctionSignature
 * @see org.example.scanner.FunctionRegistry
 */
public class CppParser {

    private static final Logger log = LoggerFactory.getLogger(CppParser.class);

    /**
     * The complete set of C++ type strings that this parser recognises as valid
     * return or parameter types. Pointer variants of these types (strings ending
     * with {@code *}) are also accepted even if not explicitly listed here.
     */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "int", "long", "double", "float", "bool", "std::string", "void", "const char*",
            "int8_t", "uint8_t", "int16_t", "uint16_t", "int32_t", "uint32_t", "int64_t", "uint64_t",
            "char", "unsigned char", "short", "unsigned short", "long long", "unsigned long long",
            "int*", "long long*", "double*", "float*", "int32_t*", "int64_t*"
    );

    /**
     * Regex that matches a C++ function signature line, optionally prefixed with
     * {@code extern "C"}. Capture groups:
     * <ol>
     *   <li>Return type (may include pointer stars)</li>
     *   <li>Function name</li>
     *   <li>Raw parameter list string (content between parentheses)</li>
     * </ol>
     */
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile(
            "^\\s*(?:extern\\s+\"C\"\\s+)?(.+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{?"
    );

    /**
     * Parses the given {@code .cpp} file and returns all {@link FunctionSignature}s
     * found for functions marked with {@code // @mite}.
     *
     * <p>Lines are read sequentially. When a {@code // @mite} marker is found,
     * the subsequent line(s) are collected into a single signature string and
     * passed to {@link #parseSignature}. If parsing succeeds, the result is added
     * to the output list and logged at {@code TRACE} level.
     *
     * <p>Functions with unrecognised types or malformed signatures are silently
     * skipped and do not appear in the result.
     *
     * @param cppFile path to the {@code .cpp} source file to parse
     * @return list of successfully parsed {@link FunctionSignature}s in file order;
     * empty if no {@code // @mite}-marked functions are found or all are invalid
     * @throws RuntimeException if the file cannot be read
     */
    public List<FunctionSignature> parse(Path cppFile) {
        try {
            List<String> lines = Files.readAllLines(cppFile);
            List<FunctionSignature> result = new ArrayList<>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.equals("// @mite") && !line.equals("//@mite")) continue;

                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < lines.size()) {
                    String nextLine = lines.get(j).trim();
                    sb.append(" ").append(nextLine);
                    if (nextLine.contains("{") || nextLine.contains(";")) {
                        break;
                    }
                    if (nextLine.startsWith("//") || nextLine.startsWith("}")) {
                        break;
                    }
                    j++;
                }

                String fullSignatureStr = sb.toString().trim();
                Optional<FunctionSignature> sig = parseSignature(fullSignatureStr);
                sig.ifPresent(functionSignature -> log.trace("Registered signature: {} -> {}", fullSignatureStr, functionSignature));
                sig.ifPresent(result::add);

                i = j - 1;
            }

            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + cppFile, e);
        }
    }

    /**
     * Attempts to parse a single C++ function signature string into a {@link FunctionSignature}.
     *
     * <p>Applies {@link #SIGNATURE_PATTERN} to extract return type, function name,
     * and raw parameter list. The return type is validated against {@link #SUPPORTED_TYPES}
     * (or accepted if it ends with {@code *}). The parameter list is forwarded to
     * {@link #parseParamTypes} for individual type extraction.
     *
     * @param line the raw signature string, possibly including {@code extern "C"} prefix
     * @return the parsed {@link FunctionSignature}, or {@link Optional#empty()} if
     * the signature could not be parsed or contains unsupported types
     */
    private Optional<FunctionSignature> parseSignature(String line) {
        Matcher m = SIGNATURE_PATTERN.matcher(line);
        if (!m.find()) return Optional.empty();

        String returnType = m.group(1).trim();
        String name = m.group(2).trim();
        String paramsRaw = m.group(3).trim();

        if (!SUPPORTED_TYPES.contains(returnType) && (!returnType.endsWith("*") || returnType.equals("void*"))) {
            return Optional.empty();
        }

        List<String> paramTypes = parseParamTypes(paramsRaw);
        if (paramTypes == null) return Optional.empty();

        return Optional.of(new FunctionSignature(name, returnType, paramTypes));
    }

    /**
     * Parses the raw parameter list string into a list of C++ type strings.
     *
     * <p>Parameters are split on commas. Each token is passed to {@link #extractType}
     * to strip the parameter name and normalise the type. If any parameter has an
     * unrecognised type, {@code null} is returned to signal that the whole function
     * signature should be rejected.
     *
     * @param paramsRaw the raw parameter list string between the parentheses
     *                  (e.g., {@code "float* data, int length, bool flag"})
     * @return an ordered list of C++ type strings, one per parameter;
     * an empty list for {@code ()} or {@code (void)};
     * {@code null} if any parameter type is not recognised
     */
    private List<String> parseParamTypes(String paramsRaw) {
        if (paramsRaw.isBlank() || paramsRaw.trim().equals("void")) return List.of();

        List<String> types = new ArrayList<>();
        for (String param : paramsRaw.split(",")) {
            String trimmed = param.trim();
            String type = extractType(trimmed);

            if (type == null || (!SUPPORTED_TYPES.contains(type) && (!type.endsWith("*") || type.equals("void*")))) {
                return null;
            }
            types.add(type);
        }
        return types;
    }

    /**
     * Extracts the C++ type from a single parameter declaration by stripping
     * the parameter name, {@code const} qualifiers, and reference symbols.
     *
     * <p>Special cases handled:
     * <ul>
     *   <li>{@code const char*} variants — always returned as {@code "const char*"}</li>
     *   <li>Pointer types — the {@code *} is preserved and attached to the base type</li>
     *   <li>{@code std::string} — matched by prefix</li>
     *   <li>Plain {@code void} as a standalone parameter — returns {@code null} (invalid param)</li>
     * </ul>
     *
     * @param param a single parameter declaration string (e.g., {@code "const float* coords"},
     *              {@code "int count"}, {@code "std::string name"})
     * @return the normalised C++ type string (e.g., {@code "float*"}, {@code "int"}),
     * or {@code null} if the type cannot be determined
     */
    private String extractType(String param) {
        if (param.matches("const\\s+char\\s*\\*.*")) return "const char*";
        if (param.trim().equals("void")) return null;

        param = param.replaceAll("(\\*+)(\\w+)", "$1 $2");

        boolean isPointer = param.contains("*") && !param.contains("char");
        param = param.replaceAll("\\bconst\\b", "").replaceAll("&", "").trim().replaceAll("\\s+", " ");

        if (SUPPORTED_TYPES.contains(param)) {
            return param;
        }

        int lastSpace = param.lastIndexOf(' ');
        if (lastSpace != -1) {
            String typeCandidate = param.substring(0, lastSpace).trim();
            typeCandidate = typeCandidate.replaceAll("\\s+\\*", "*");

            if (isPointer && !typeCandidate.endsWith("*")) {
                typeCandidate += "*";
            }
            if (typeCandidate.endsWith("*") || SUPPORTED_TYPES.contains(typeCandidate)) {
                return typeCandidate;
            }
        }

        if (param.startsWith("std::string")) return "std::string";

        return null;
    }
}