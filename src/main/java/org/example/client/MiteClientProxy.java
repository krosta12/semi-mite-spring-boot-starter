package org.example.client;

import org.example.engine.CppEngine;

import java.lang.reflect.*;

public class MiteClientProxy implements InvocationHandler {

    private final CppEngine engine;
    private final String script;

    public MiteClientProxy(CppEngine engine, String script) {
        this.engine = engine;
        this.script = script;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(method, args);
        }

        Object[] safeArgs = args != null ? args : new Object[0];
        return engine.execute(method.getName(), safeArgs);
    }

    private Object handleObjectMethod(Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "MiteClient[script=" + script + "]";
            case "hashCode" -> System.identityHashCode(this);
            case "equals" -> this == args[0];
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> interfaceClass, CppEngine engine, String script) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class[]{interfaceClass},
                new MiteClientProxy(engine, script)
        );
    }
}