package org.example.client;

import org.example.engine.CppEngine;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class MiteClientFactoryBean implements FactoryBean<Object>, ApplicationContextAware {

    private Class<?> interfaceClass;
    private String script;
    private ApplicationContext applicationContext;

    public MiteClientFactoryBean() {
    }

    public MiteClientFactoryBean(Class<?> interfaceClass, String script) {
        this.interfaceClass = interfaceClass;
        this.script = script;
    }

    @Override
    public Object getObject() {
        CppEngine engine = applicationContext.getBean(CppEngine.class);
        return MiteClientProxy.create(interfaceClass, engine, script);
    }

    @Override
    public Class<?> getObjectType() {
        return interfaceClass;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void setInterfaceClass(Class<?> interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    public void setScript(String script) {
        this.script = script;
    }
}