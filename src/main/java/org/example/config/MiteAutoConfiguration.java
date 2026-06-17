package org.example.config;

import org.example.compiler.CppCompiler;
import org.example.engine.CppEngine;
import org.example.engine.DefaultCppEngine;
import org.example.scanner.FunctionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MiteProperties.class)
public class MiteAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CppCompiler cppCompiler(MiteProperties props) {
        return new CppCompiler(props.getCacheDir(), props.getCompilerPath());
    }

    @Bean
    @ConditionalOnMissingBean
    public FunctionRegistry functionRegistry(MiteProperties props) {
        return new FunctionRegistry(props.getScriptsDir());
    }

    @Bean
    @ConditionalOnMissingBean
    public CppEngine cppEngine(CppCompiler compiler, FunctionRegistry registry) {
        return new DefaultCppEngine(compiler, registry);
    }
}