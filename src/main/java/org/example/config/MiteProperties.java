package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "mite")
public class MiteProperties {

    private Path cacheDir = Path.of(".mite-cache");
    private Path scriptsDir = Path.of("cppScripts");

    private String compilerPath = null;

    public String getCompilerPath() {
        return compilerPath;
    }

    public void setCompilerPath(String compilerPath) {
        this.compilerPath = compilerPath;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public Path getScriptsDir() {
        return scriptsDir;
    }

    public void setScriptsDir(Path scriptsDir) {
        this.scriptsDir = scriptsDir;
    }
}