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

package org.mortbay.jetty.jsp;


/**
 * 
 */
public class JettyJuliLog implements org.apache.juli.logging.Log 
{
    public static org.apache.juli.logging.Log getInstance(String name)
    {
        return new JettyJuliLog(org.eclipse.jetty.util.log.Log.getLogger(name));
    }
    
    private final org.eclipse.jetty.util.log.Logger _logger;
    private final org.eclipse.jetty.util.log.StdErrLog _stdErrLog;
    
    public JettyJuliLog(org.eclipse.jetty.util.log.Logger logger)
    {
        _logger=logger;
        _stdErrLog=(logger instanceof org.eclipse.jetty.util.log.StdErrLog) ? (org.eclipse.jetty.util.log.StdErrLog)logger:null;
    }
    
    @Override
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled()
    {
        return _stdErrLog==null?true:_stdErrLog.getLevel()<=org.eclipse.jetty.util.log.StdErrLog.LEVEL_WARN;
    }

    @Override
    public boolean isFatalEnabled()
    {
        return _stdErrLog==null?true:_stdErrLog.getLevel()<=org.eclipse.jetty.util.log.StdErrLog.LEVEL_WARN;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return _stdErrLog==null?true:_stdErrLog.getLevel()<=org.eclipse.jetty.util.log.StdErrLog.LEVEL_INFO;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return _stdErrLog==null?true:_stdErrLog.getLevel()<=org.eclipse.jetty.util.log.StdErrLog.LEVEL_DEBUG;
    }

    @Override
    public boolean isWarnEnabled()
    {
        return _stdErrLog==null?true:_stdErrLog.getLevel()<=org.eclipse.jetty.util.log.StdErrLog.LEVEL_WARN;
    }

    @Override
    public void trace(Object message)
    {
        if (message instanceof String)
            _logger.debug((String)message);
        else
            _logger.debug("{}",message);
    }

    @Override
    public void trace(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.debug((String)message,t);
        else
            _logger.debug("{}",message,t);
    }

    @Override
    public void debug(Object message)
    {
        if (message instanceof String)
            _logger.debug((String)message);
        else
            _logger.debug("{}",message);
    }

    @Override
    public void debug(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.debug((String)message,t);
        else
            _logger.debug("{}",message,t);
    }

    @Override
    public void info(Object message)
    {
        if (message instanceof String)
            _logger.info((String)message);
        else
            _logger.info("{}",message);
    }

    @Override
    public void info(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.info((String)message,t);
        else
            _logger.info("{}",message,t);
    }

    @Override
    public void warn(Object message)
    {
        if (message instanceof String)
            _logger.warn((String)message);
        else
            _logger.warn("{}",message);
    }

    @Override
    public void warn(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.warn((String)message,t);
        else
            _logger.warn("{}",message,t);
    }

    @Override
    public void error(Object message)
    {
        if (message instanceof String)
            _logger.warn((String)message);
        else
            _logger.warn("{}",message);
    }

    @Override
    public void error(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.warn((String)message,t);
        else
            _logger.warn("{}",message,t);
    }

    @Override
    public void fatal(Object message)
    {
        if (message instanceof String)
            _logger.warn((String)message);
        else
            _logger.warn("{}",message);
    }

    @Override
    public void fatal(Object message, Throwable t)
    {
        if (message instanceof String)
            _logger.warn((String)message,t);
        else
            _logger.warn("{}",message,t);
    }
}


