//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.apache.tomcat;

import java.lang.reflect.InvocationTargetException;

import javax.naming.NamingException;

/**
 * SimpleInstanceManager
 * 
 * Implement the org.apache.tomcat.InstanceManager interface.
 */
public class SimpleInstanceManager implements InstanceManager
{
    public SimpleInstanceManager()
    {    
    }
    
    @Override
    public Object newInstance(Class<?> clazz) throws IllegalAccessException, InvocationTargetException, NamingException, InstantiationException
    {
        return prepareInstance(clazz.newInstance());
    }

    @Override
    public Object newInstance(String className) throws IllegalAccessException, InvocationTargetException, NamingException, InstantiationException,
            ClassNotFoundException
    {
        Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
        return prepareInstance(clazz.newInstance());
    }

    @Override
    public Object newInstance(String fqcn, ClassLoader classLoader) throws IllegalAccessException, InvocationTargetException, NamingException,
            InstantiationException, ClassNotFoundException
    {
        Class<?> clazz = classLoader.loadClass(fqcn);
        return prepareInstance(clazz.newInstance());
    }

    @Override
    public void newInstance(Object o) throws IllegalAccessException, InvocationTargetException, NamingException
    {
        prepareInstance(o);
    }

    @Override
    public void destroyInstance(Object o) throws IllegalAccessException, InvocationTargetException
    {
        // TODO - call preDestroy?
    }

    private Object prepareInstance(Object o)
    {
        // TODO - inject, postConstruct etc?
        return o;
    }

}
