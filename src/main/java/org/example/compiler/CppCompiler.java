package org.example.compiler;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CppCompiler {

    private final Path cacheDir;
    private final Map<String, CachedLib> cache = new ConcurrentHashMap<>();

    private final String customCompilerPath;


    public CppCompiler(Path cacheDir, String customCompilerPath) {
        this.cacheDir = cacheDir;
        this.customCompilerPath = customCompilerPath;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new MiteException("Error during cache creation: " + cacheDir, e);
        }
    }

    public Path compile(Path cppFile) {
        String key = cppFile.toAbsolutePath().toString();
        long lastModified = lastModified(cppFile);

        CachedLib cached = cache.get(key);
        if (cached != null && cached.timestamp == lastModified) {
            return cached.lib;
        }

        Path preprocessed = preprocessAndCompile(cppFile);
        Path lib = doCompile(preprocessed);
        cache.put(key, new CachedLib(lib, lastModified));
        return lib;
    }

    public Path compileInline(String code) {
        String key = "inline:" + code.hashCode();
        CachedLib cached = cache.get(key);
        if (cached != null) return cached.lib;

        try {
            Path tmp = Files.createTempFile(cacheDir, "mite_inline_", ".cpp");
            Files.writeString(tmp, code);
            Path lib = doCompile(tmp);
            cache.put(key, new CachedLib(lib, -1));
            return lib;
        } catch (IOException e) {
            throw new MiteException("Inline code writing error", e);
        }
    }

    private Path doCompile(Path cppFile) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String name = cppFile.getFileName().toString().replace(".cpp", "") + "_" + System.nanoTime();
        String libName = win ? name + ".dll" : "lib" + name + ".so";
        Path out = cacheDir.resolve(libName);

        String compiler = findCompiler();

        List<String> cmd = new ArrayList<>();
        cmd.add(compiler);
        cmd.add("-shared");
        if (!win) cmd.add("-fPIC");
        cmd.add("-O2");
        if (win) cmd.add("-Wl,--kill-at");
        cmd.add("-o");
        cmd.add(out.toAbsolutePath().toString());
        cmd.add(cppFile.toAbsolutePath().toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (win) {
                pb.environment().put("PATH",
                        "C:\\msys64\\ucrt64\\bin;" + pb.environment().getOrDefault("PATH", ""));
            }
            pb.redirectErrorStream(false);
            Process p = pb.start();

            String stdout = new String(p.getInputStream().readAllBytes());
            String stderr = new String(p.getErrorStream().readAllBytes());
            int exitCode = p.waitFor();

            if (exitCode != 0) throw new MiteException("Compilation error:\n" + stdout + "\n" + stderr);
            return out;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiteException("Failed to start compiler: " + compiler, e);
        }
    }

    private String findCompiler() {
        if (customCompilerPath != null && !customCompilerPath.isBlank()) {
            return customCompilerPath;
        }

        List<String> candidates = new ArrayList<>();

        candidates.add("g++");
        candidates.add("clang++");

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            candidates.addAll(findWindowsCompilers());
        }

        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version")
                        .redirectErrorStream(true)
                        .start();
                p.waitFor();
                if (p.exitValue() == 0) return c;
            } catch (Exception ignored) {
            }
        }

        throw new MiteException(
                "C++ compiler not found. Install g++ or clang++ and add to PATH, " +
                        "or specify explicitly: mite.compiler-path=C:\\path\\to\\g++.exe"
        );
    }

    private List<String> findWindowsCompilers() {
        List<String> found = new ArrayList<>();

        List<String> msys2Roots = List.of(
                "C:\\msys64",
                "C:\\msys2",
                System.getenv().getOrDefault("MSYS2_ROOT", ""),
                System.getProperty("user.home") + "\\msys64"
        );

        List<String> msys2SubDirs = List.of(
                "ucrt64\\bin\\g++.exe",
                "mingw64\\bin\\g++.exe",
                "mingw32\\bin\\g++.exe",
                "clang64\\bin\\clang++.exe"
        );

        for (String root : msys2Roots) {
            if (root.isBlank()) continue;
            for (String sub : msys2SubDirs) {
                Path path = Path.of(root, sub);
                if (Files.exists(path)) {
                    found.add(path.toString());
                }
            }
        }

        String userHome = System.getProperty("user.home");
        List<String> scoopPaths = List.of(
                userHome + "\\scoop\\apps\\gcc\\current\\bin\\g++.exe",
                userHome + "\\scoop\\apps\\llvm\\current\\bin\\clang++.exe"
        );
        for (String p : scoopPaths) {
            if (Files.exists(Path.of(p))) found.add(p);
        }

        return found;
    }


    private Path preprocessAndCompile(Path cppFile) {
        try {
            List<String> lines = Files.readAllLines(cppFile);
            List<String> result = new ArrayList<>();
            result.add("#include <string>");
            result.add("#include <cstring>");
            result.add("");

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();

                if (trimmed.equals("// @mite") && i + 1 < lines.size()) {
                    String next = lines.get(i + 1).trim();
                    result.add(line);

                    if (next.startsWith("extern")) {
                        result.add(lines.get(i + 1));
                    } else {
                        result.add("extern \"C\" " + lines.get(i + 1));
                    }
                    i++;
                    continue;
                }
                result.add(line);
            }

            Path temp = cacheDir.resolve(cppFile.getFileName());
            Files.write(temp, result);
            return temp;

        } catch (IOException e) {
            throw new MiteException("Preprocessing error: " + cppFile, e);
        }
    }

    private long lastModified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private record CachedLib(Path lib, long timestamp) {
    }
}