package org.example.client;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(MiteClientsRegistrar.class)
public @interface EnableMiteClients {
    String[] basePackages() default {};
}