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

package org.apache.juli.logging;


/**
 * A Naive impl that should be replaced by actual usages
 */
public class SimpleLog implements org.apache.juli.logging.Log 
{
    public static org.apache.juli.logging.Log getInstance(String name)
    {
        return new SimpleLog();
    }

    @Override
    public boolean isDebugEnabled()
    {
        return false;
    }

    @Override
    public boolean isErrorEnabled()
    {
        return true;
    }

    @Override
    public boolean isFatalEnabled()
    {
        return true;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return true;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return false;
    }

    @Override
    public boolean isWarnEnabled()
    {
        return true;
    }

    @Override
    public void trace(Object message)
    {
    }

    @Override
    public void trace(Object message, Throwable t)
    {
    }

    @Override
    public void debug(Object message)
    {
    }

    @Override
    public void debug(Object message, Throwable t)
    {
    }

    @Override
    public void info(Object message)
    {
        System.err.printf("%t INFO %s%n",System.currentTimeMillis(),message);
    }

    @Override
    public void info(Object message, Throwable t)
    {
        System.err.printf("%t INFO %s%n",System.currentTimeMillis(),message);
        t.printStackTrace();
    }

    @Override
    public void warn(Object message)
    {
        System.err.printf("%t WARN %s%n",System.currentTimeMillis(),message);
    }

    @Override
    public void warn(Object message, Throwable t)
    {
        System.err.printf("%t WARN %s%n",System.currentTimeMillis(),message);
        t.printStackTrace();
    }

    @Override
    public void error(Object message)
    {       
        System.err.printf("%t ERROR %s%n",System.currentTimeMillis(),message);
    }

    @Override
    public void error(Object message, Throwable t)
    {
        System.err.printf("%t ERROR %s%n",System.currentTimeMillis(),message);
        t.printStackTrace();
    }

    @Override
    public void fatal(Object message)
    {        
        System.err.printf("%t FATAL %s%n",System.currentTimeMillis(),message);
    }

    @Override
    public void fatal(Object message, Throwable t)
    {
        System.err.printf("%t FATAL %s%n",System.currentTimeMillis(),message); 
        t.printStackTrace();       
    }
    
}


