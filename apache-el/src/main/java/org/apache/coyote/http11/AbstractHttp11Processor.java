/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpUpgradeHandler;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ActionCode;
import org.apache.coyote.AsyncContextCallback;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.IdentityOutputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.coyote.http11.filters.VoidOutputFilter;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.Ascii;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;
import org.apache.tomcat.util.res.StringManager;

public abstract class AbstractHttp11Processor<S> extends AbstractProcessor<S> {

    protected abstract Log getLog();
    private final UserDataHelper userDataHelper;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    /**
     * Input.
     */
    protected AbstractInputBuffer<S> inputBuffer ;


    /**
     * Output.
     */
    protected AbstractOutputBuffer<S> outputBuffer;


    /*
     * Tracks how many internal filters are in the filter library so they
     * are skipped when looking for pluggable filters.
     */
    private int pluggableFilterIndex = Integer.MAX_VALUE;


    /**
     * Error flag.
     */
    protected boolean error = false;


    /**
     * Keep-alive.
     */
    protected boolean keepAlive = true;


    /**
     * Flag used to indicate that the socket should be kept open (e.g. for keep
     * alive or send file.
     */
    protected boolean openSocket = false;


    /**
     * Flag used to indicate that the socket should treat the next request
     * processed like a keep-alive connection - i.e. one where there may not be
     * any data to process. The initial value of this flag on entering the
     * process method is different for connectors that use polling (NIO / APR -
     * data is always expected) compared to those that use blocking (BIO - data
     * is only expected if the connection isn't in the keep-alive state).
     */
    protected boolean keptAlive;


    /**
     * Flag that indicates that send file processing is in progress and that the
     * socket should not be returned to the poller (where a poller is used).
     */
    protected boolean sendfileInProgress = false;


    /**
     * Flag that indicates if the request headers have been completely read.
     */
    protected boolean readComplete = true;

    /**
     * HTTP/1.1 flag.
     */
    protected boolean http11 = true;


    /**
     * HTTP/0.9 flag.
     */
    protected boolean http09 = false;


    /**
     * Content delimiter for the request (if false, the connection will
     * be closed at the end of the request).
     */
    protected boolean contentDelimitation = true;


    /**
     * Is there an expectation ?
     */
    protected boolean expectation = false;


    /**
     * Comet used.
     */
    protected boolean comet = false;


    /**
     * Regular expression that defines the restricted user agents.
     */
    protected Pattern restrictedUserAgents = null;


    /**
     * Maximum number of Keep-Alive requests to honor.
     */
    protected int maxKeepAliveRequests = -1;

    /**
     * The number of seconds Tomcat will wait for a subsequent request
     * before closing the connection.
     */
    protected int keepAliveTimeout = -1;

    /**
     * Maximum timeout on uploads. 5 minutes as in Apache HTTPD server.
     */
    protected int connectionUploadTimeout = 300000;


    /**
     * Flag to disable setting a different time-out on uploads.
     */
    protected boolean disableUploadTimeout = false;


    /**
     * Allowed compression level.
     */
    protected int compressionLevel = 0;


    /**
     * Minimum content size to make compression.
     */
    protected int compressionMinSize = 2048;


    /**
     * Max saved post size.
     */
    protected int maxSavePostSize = 4 * 1024;


    /**
     * Regular expression that defines the user agents to not use gzip with
     */
    protected Pattern noCompressionUserAgents = null;

    /**
     * List of MIMES which could be gzipped
     */
    protected String[] compressableMimeTypes =
    { "text/html", "text/xml", "text/plain" };


    /**
     * Host name (used to avoid useless B2C conversion on the host name).
     */
    protected char[] hostNameC = new char[0];


    /**
     * Allow a customized the server header for the tin-foil hat folks.
     */
    protected String server = null;


    /**
     * Instance of the new protocol to use after the HTTP connection has been
     * upgraded.
     */
    protected HttpUpgradeHandler httpUpgradeHandler = null;


    public AbstractHttp11Processor(AbstractEndpoint<S> endpoint) {
        super(endpoint);
        userDataHelper = new UserDataHelper(getLog());
    }


    /**
     * Set compression level.
     */
    public void setCompression(String compression) {
        if (compression.equals("on")) {
            this.compressionLevel = 1;
        } else if (compression.equals("force")) {
            this.compressionLevel = 2;
        } else if (compression.equals("off")) {
            this.compressionLevel = 0;
        } else {
            try {
                // Try to parse compression as an int, which would give the
                // minimum compression size
                compressionMinSize = Integer.parseInt(compression);
                this.compressionLevel = 1;
            } catch (Exception e) {
                this.compressionLevel = 0;
            }
        }
    }

    /**
     * Set Minimum size to trigger compression.
     */
    public void setCompressionMinSize(int compressionMinSize) {
        this.compressionMinSize = compressionMinSize;
    }


    /**
     * Set no compression user agent pattern. Regular expression as supported
     * by {@link Pattern}.
     *
     * ie: "gorilla|desesplorer|tigrus"
     */
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        if (noCompressionUserAgents == null || noCompressionUserAgents.length() == 0) {
            this.noCompressionUserAgents = null;
        } else {
            this.noCompressionUserAgents =
                Pattern.compile(noCompressionUserAgents);
        }
    }

    /**
     * Add a mime-type which will be compressible
     * The mime-type String will be exactly matched
     * in the response mime-type header .
     *
     * @param mimeType mime-type string
     */
    public void addCompressableMimeType(String mimeType) {
        compressableMimeTypes =
            addStringArray(compressableMimeTypes, mimeType);
    }


    /**
     * Set compressible mime-type list (this method is best when used with
     * a large number of connectors, where it would be better to have all of
     * them referenced a single array).
     */
    public void setCompressableMimeTypes(String[] compressableMimeTypes) {
        this.compressableMimeTypes = compressableMimeTypes;
    }


    /**
     * Set compressable mime-type list
     * List contains users agents separated by ',' :
     *
     * ie: "text/html,text/xml,text/plain"
     */
    public void setCompressableMimeTypes(String compressableMimeTypes) {
        if (compressableMimeTypes != null) {
            this.compressableMimeTypes = null;
            StringTokenizer st = new StringTokenizer(compressableMimeTypes, ",");

            while (st.hasMoreTokens()) {
                addCompressableMimeType(st.nextToken().trim());
            }
        }
    }


    /**
     * Return compression level.
     */
    public String getCompression() {
        switch (compressionLevel) {
        case 0:
            return "off";
        case 1:
            return "on";
        case 2:
            return "force";
        }
        return "off";
    }


    /**
     * General use method
     *
     * @param sArray the StringArray
     * @param value string
     */
    private String[] addStringArray(String sArray[], String value) {
        String[] result = null;
        if (sArray == null) {
            result = new String[1];
            result[0] = value;
        }
        else {
            result = new String[sArray.length + 1];
            for (int i = 0; i < sArray.length; i++) {
                result[i] = sArray[i];
            }
            result[sArray.length] = value;
        }
        return result;
    }


    /**
     * Checks if any entry in the string array starts with the specified value
     *
     * @param sArray the StringArray
     * @param value string
     */
    private boolean startsWithStringArray(String sArray[], String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < sArray.length; i++) {
            if (value.startsWith(sArray[i])) {
                return true;
            }
        }
        return false;
    }


    /**
     * Set restricted user agent list (which will downgrade the connector
     * to HTTP/1.0 mode). Regular expression as supported by {@link Pattern}.
     *
     * ie: "gorilla|desesplorer|tigrus"
     */
    public void setRestrictedUserAgents(String restrictedUserAgents) {
        if (restrictedUserAgents == null ||
                restrictedUserAgents.length() == 0) {
            this.restrictedUserAgents = null;
        } else {
            this.restrictedUserAgents = Pattern.compile(restrictedUserAgents);
        }
    }


    /**
     * Set the maximum number of Keep-Alive requests to honor.
     * This is to safeguard from DoS attacks.  Setting to a negative
     * value disables the check.
     */
    public void setMaxKeepAliveRequests(int mkar) {
        maxKeepAliveRequests = mkar;
    }


    /**
     * Return the number of Keep-Alive requests that we will honor.
     */
    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }

    /**
     * Set the Keep-Alive timeout.
     */
    public void setKeepAliveTimeout(int timeout) {
        keepAliveTimeout = timeout;
    }


    /**
     * Return the number Keep-Alive timeout.
     */
    public int getKeepAliveTimeout() {
        return keepAliveTimeout;
    }


    /**
     * Set the maximum size of a POST which will be buffered in SSL mode.
     */
    public void setMaxSavePostSize(int msps) {
        maxSavePostSize = msps;
    }


    /**
     * Return the maximum size of a POST which will be buffered in SSL mode.
     */
    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }


    /**
     * Set the flag to control upload time-outs.
     */
    public void setDisableUploadTimeout(boolean isDisabled) {
        disableUploadTimeout = isDisabled;
    }

    /**
     * Get the flag that controls upload time-outs.
     */
    public boolean getDisableUploadTimeout() {
        return disableUploadTimeout;
    }

    /**
     * Set the socket buffer flag.
     */
    public void setSocketBuffer(int socketBuffer) {
        outputBuffer.setSocketBuffer(socketBuffer);
    }

    /**
     * Get the socket buffer flag.
     */
    public int getSocketBuffer() {
        return outputBuffer.getSocketBuffer();
    }

    /**
     * Set the upload timeout.
     */
    public void setConnectionUploadTimeout(int timeout) {
        connectionUploadTimeout = timeout ;
    }

    /**
     * Get the upload timeout.
     */
    public int getConnectionUploadTimeout() {
        return connectionUploadTimeout;
    }


    /**
     * Set the server header name.
     */
    public void setServer( String server ) {
        if (server==null || server.equals("")) {
            this.server = null;
        } else {
            this.server = server;
        }
    }

    /**
     * Get the server header name.
     */
    public String getServer() {
        return server;
    }


    /**
     * Check if the resource could be compressed, if the client supports it.
     */
    private boolean isCompressable() {

        // Check if content is not already gzipped
        MessageBytes contentEncodingMB =
            response.getMimeHeaders().getValue("Content-Encoding");

        if ((contentEncodingMB != null)
            && (contentEncodingMB.indexOf("gzip") != -1)) {
            return false;
        }

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2) {
            return true;
        }

        // Check if sufficient length to trigger the compression
        long contentLength = response.getContentLengthLong();
        if ((contentLength == -1)
            || (contentLength > compressionMinSize)) {
            // Check for compatible MIME-TYPE
            if (compressableMimeTypes != null) {
                return (startsWithStringArray(compressableMimeTypes,
                                              response.getContentType()));
            }
        }

        return false;
    }


    /**
     * Check if compression should be used for this resource. Already checked
     * that the resource could be compressed if the client supports it.
     */
    private boolean useCompression() {

        // Check if browser support gzip encoding
        MessageBytes acceptEncodingMB =
            request.getMimeHeaders().getValue("accept-encoding");

        if ((acceptEncodingMB == null)
            || (acceptEncodingMB.indexOf("gzip") == -1)) {
            return false;
        }

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2) {
            return true;
        }

        // Check for incompatible Browser
        if (noCompressionUserAgents != null) {
            MessageBytes userAgentValueMB =
                request.getMimeHeaders().getValue("user-agent");
            if(userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();

                if (noCompressionUserAgents != null &&
                        noCompressionUserAgents.matcher(userAgentValue).matches()) {
                        return false;
                }
            }
        }

        return true;
    }


    /**
     * Specialized utility method: find a sequence of lower case bytes inside
     * a ByteChunk.
     */
    protected int findBytes(ByteChunk bc, byte[] b) {

        byte first = b[0];
        byte[] buff = bc.getBuffer();
        int start = bc.getStart();
        int end = bc.getEnd();

        // Look for first char
        int srcEnd = b.length;

        for (int i = start; i <= (end - srcEnd); i++) {
            if (Ascii.toLower(buff[i]) != first) {
                continue;
            }
            // found first char, now look for a match
            int myPos = i+1;
            for (int srcPos = 1; srcPos < srcEnd;) {
                if (Ascii.toLower(buff[myPos++]) != b[srcPos++]) {
                    break;
                }
                if (srcPos == srcEnd) {
                    return i - start; // found it
                }
            }
        }
        return -1;
    }


    /**
     * Determine if we must drop the connection because of the HTTP status
     * code.  Use the same list of codes as Apache/httpd.
     */
    protected boolean statusDropsConnection(int status) {
        return status == 400 /* SC_BAD_REQUEST */ ||
               status == 408 /* SC_REQUEST_TIMEOUT */ ||
               status == 411 /* SC_LENGTH_REQUIRED */ ||
               status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
               status == 414 /* SC_REQUEST_URI_TOO_LONG */ ||
               status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
               status == 503 /* SC_SERVICE_UNAVAILABLE */ ||
               status == 501 /* SC_NOT_IMPLEMENTED */;
    }


    /**
     * Exposes input buffer to super class to allow better code re-use.
     * @return  The input buffer used by the processor.
     */
    protected abstract AbstractInputBuffer<S> getInputBuffer();


    /**
     * Exposes output buffer to super class to allow better code re-use.
     * @return  The output buffer used by the processor.
     */
    protected abstract AbstractOutputBuffer<S> getOutputBuffer();


    /**
     * Initialize standard input and output filters.
     */
    protected void initializeFilters(int maxTrailerSize, int maxExtensionSize) {
        // Create and add the identity filters.
        getInputBuffer().addFilter(new IdentityInputFilter());
        getOutputBuffer().addFilter(new IdentityOutputFilter());

        // Create and add the chunked filters.
        getInputBuffer().addFilter(
                new ChunkedInputFilter(maxTrailerSize, maxExtensionSize));
        getOutputBuffer().addFilter(new ChunkedOutputFilter());

        // Create and add the void filters.
        getInputBuffer().addFilter(new VoidInputFilter());
        getOutputBuffer().addFilter(new VoidOutputFilter());

        // Create and add buffered input filter
        getInputBuffer().addFilter(new BufferedInputFilter());

        // Create and add the chunked filters.
        //getInputBuffer().addFilter(new GzipInputFilter());
        getOutputBuffer().addFilter(new GzipOutputFilter());

        pluggableFilterIndex = getInputBuffer().getFilters().length;
    }


    /**
     * Add an input filter to the current request.
     *
     * @return false if the encoding was not found (which would mean it is
     * unsupported)
     */
    protected boolean addInputFilter(InputFilter[] inputFilters,
                                     String encodingName) {
        if (encodingName.equals("identity")) {
            // Skip
        } else if (encodingName.equals("chunked")) {
            getInputBuffer().addActiveFilter
                (inputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
        } else {
            for (int i = pluggableFilterIndex; i < inputFilters.length; i++) {
                if (inputFilters[i].getEncodingName()
                    .toString().equals(encodingName)) {
                    getInputBuffer().addActiveFilter(inputFilters[i]);
                    return true;
                }
            }
            return false;
        }
        return true;
    }


    /**
     * Send an action to the connector.
     *
     * @param actionCode Type of the action
     * @param param Action parameter
     */
    @Override
    public final void action(ActionCode actionCode, Object param) {

        if (actionCode == ActionCode.CLOSE) {
            // End the processing of the current request

            try {
                getOutputBuffer().endRequest();
            } catch (IOException e) {
                // Set error flag
                error = true;
            }

        } else if (actionCode == ActionCode.COMMIT) {
            // Commit current response

            if (response.isCommitted()) {
                return;
            }

            // Validate and write response headers
            try {
                prepareResponse();
                getOutputBuffer().commit();
            } catch (IOException e) {
                // Set error flag
                error = true;
            }

        } else if (actionCode == ActionCode.ACK) {
            // Acknowledge request
            // Send a 100 status back if it makes sense (response not committed
            // yet, and client specified an expectation for 100-continue)

            if ((response.isCommitted()) || !expectation) {
                return;
            }

            getInputBuffer().setSwallowInput(true);
            try {
                getOutputBuffer().sendAck();
            } catch (IOException e) {
                // Set error flag
                error = true;
            }
        } else if (actionCode == ActionCode.CLIENT_FLUSH) {

            try {
                getOutputBuffer().flush();
            } catch (IOException e) {
                // Set error flag
                error = true;
                response.setErrorException(e);
            }

        } else if (actionCode == ActionCode.IS_ERROR) {
            ((AtomicBoolean) param).set(error);

        } else if (actionCode == ActionCode.DISABLE_SWALLOW_INPUT) {
            // Do not swallow request input but
            // make sure we are closing the connection
            error = true;
            getInputBuffer().setSwallowInput(false);

        } else if (actionCode == ActionCode.RESET) {
            // Reset response
            // Note: This must be called before the response is committed

            getOutputBuffer().reset();

        } else if (actionCode == ActionCode.REQ_SET_BODY_REPLAY) {
            ByteChunk body = (ByteChunk) param;

            InputFilter savedBody = new SavedRequestInputFilter(body);
            savedBody.setRequest(request);

            @SuppressWarnings("unchecked")
            AbstractInputBuffer<S> internalBuffer = (AbstractInputBuffer<S>)
                request.getInputBuffer();
            internalBuffer.addActiveFilter(savedBody);
        } else if (actionCode == ActionCode.ASYNC_START) {
            asyncStateMachine.asyncStart((AsyncContextCallback) param);
        } else if (actionCode == ActionCode.ASYNC_DISPATCHED) {
            asyncStateMachine.asyncDispatched();
        } else if (actionCode == ActionCode.ASYNC_TIMEOUT) {
            AtomicBoolean result = (AtomicBoolean) param;
            result.set(asyncStateMachine.asyncTimeout());
        } else if (actionCode == ActionCode.ASYNC_RUN) {
            asyncStateMachine.asyncRun((Runnable) param);
        } else if (actionCode == ActionCode.ASYNC_ERROR) {
            asyncStateMachine.asyncError();
        } else if (actionCode == ActionCode.ASYNC_IS_STARTED) {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncStarted());
        } else if (actionCode == ActionCode.ASYNC_IS_DISPATCHING) {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncDispatching());
        } else if (actionCode == ActionCode.ASYNC_IS_ASYNC) {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsync());
        } else if (actionCode == ActionCode.ASYNC_IS_TIMINGOUT) {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncTimingOut());
        } else if (actionCode == ActionCode.ASYNC_IS_ERROR) {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncError());
        } else if (actionCode == ActionCode.UPGRADE) {
            httpUpgradeHandler = (HttpUpgradeHandler) param;
            // Stop further HTTP output
            getOutputBuffer().finished = true;
        } else if (actionCode == ActionCode.AVAILABLE) {
            request.setAvailable(inputBuffer.available());
        } else if (actionCode == ActionCode.NB_WRITE_INTEREST) {
            AtomicBoolean isReady = (AtomicBoolean)param;
            try {
                isReady.set(getOutputBuffer().isReady());
            } catch (IOException e) {
                getLog().debug("isReady() failed", e);
                error = true;
            }
        } else if (actionCode == ActionCode.NB_READ_INTEREST) {
            registerForEvent(true, false);
        } else if (actionCode == ActionCode.REQUEST_BODY_FULLY_READ) {
            AtomicBoolean result = (AtomicBoolean) param;
            result.set(getInputBuffer().isFinished());
        } else if (actionCode == ActionCode.DISPATCH_READ) {
            socketWrapper.addDispatch(DispatchType.NON_BLOCKING_READ);
        } else if (actionCode == ActionCode.DISPATCH_WRITE) {
            socketWrapper.addDispatch(DispatchType.NON_BLOCKING_WRITE);
        } else if (actionCode == ActionCode.DISPATCH_EXECUTE) {
            getEndpoint().executeNonBlockingDispatches(socketWrapper);
        } else {
            actionInternal(actionCode, param);
        }
    }

    abstract void actionInternal(ActionCode actionCode, Object param);


    /**
     * Processors (currently only HTTP BIO) may elect to disable HTTP keep-alive
     * in some circumstances. This method allows the processor implementation to
     * determine if keep-alive should be disabled or not.
     */
    protected abstract boolean disableKeepAlive();


    /**
     * Configures the timeout to be used for reading the request line.
     */
    protected abstract void setRequestLineReadTimeout() throws IOException;


    /**
     * Defines how a connector handles an incomplete request line read.
     *
     * @return <code>true</code> if the processor should break out of the
     *         processing loop, otherwise <code>false</code>.
     */
    protected abstract boolean handleIncompleteRequestLineRead();


    /**
     * Set the socket timeout.
     */
    protected abstract void setSocketTimeout(int timeout) throws IOException;


    /**
     * Process pipelined HTTP requests using the specified input and output
     * streams.
     *
     * @param socketWrapper Socket from which the HTTP requests will be read
     *               and the HTTP responses will be written.
     *
     * @throws IOException error during an I/O operation
     */
    @Override
    public SocketState process(SocketWrapper<S> socketWrapper)
        throws IOException {
        RequestInfo rp = request.getRequestProcessor();
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

        // Setting up the I/O
        setSocketWrapper(socketWrapper);
        getInputBuffer().init(socketWrapper, endpoint);
        getOutputBuffer().init(socketWrapper, endpoint);

        // Flags
        error = false;
        keepAlive = true;
        comet = false;
        openSocket = false;
        sendfileInProgress = false;
        readComplete = true;
        if (endpoint.getUsePolling()) {
            keptAlive = false;
        } else {
            keptAlive = socketWrapper.isKeptAlive();
        }

        if (disableKeepAlive()) {
            socketWrapper.setKeepAliveLeft(0);
        }

        while (!error && keepAlive && !comet && !isAsync() &&
                httpUpgradeHandler == null && !endpoint.isPaused()) {

            // Parsing the request header
            try {
                setRequestLineReadTimeout();

                if (!getInputBuffer().parseRequestLine(keptAlive)) {
                    if (handleIncompleteRequestLineRead()) {
                        break;
                    }
                }

                if (endpoint.isPaused()) {
                    // 503 - Service unavailable
                    response.setStatus(503);
                    error = true;
                } else {
                    // Make sure that connectors that are non-blocking during
                    // header processing (NIO) only set the start time the first
                    // time a request is processed.
                    if (request.getStartTime() < 0) {
                        request.setStartTime(System.currentTimeMillis());
                    }
                    keptAlive = true;
                    // Set this every time in case limit has been changed via JMX
                    request.getMimeHeaders().setLimit(endpoint.getMaxHeaderCount());
                    // Currently only NIO will ever return false here
                    if (!getInputBuffer().parseHeaders()) {
                        // We've read part of the request, don't recycle it
                        // instead associate it with the socket
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    if (!disableUploadTimeout) {
                        setSocketTimeout(connectionUploadTimeout);
                    }
                }
            } catch (IOException e) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug(
                            sm.getString("http11processor.header.parse"), e);
                }
                error = true;
                break;
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                UserDataHelper.Mode logMode = userDataHelper.getNextMode();
                if (logMode != null) {
                    String message = sm.getString(
                            "http11processor.header.parse");
                    switch (logMode) {
                        case INFO_THEN_DEBUG:
                            message += sm.getString(
                                    "http11processor.fallToDebug");
                            //$FALL-THROUGH$
                        case INFO:
                            getLog().info(message);
                            break;
                        case DEBUG:
                            getLog().debug(message);
                    }
                }
                // 400 - Bad Request
                response.setStatus(400);
                getAdapter().log(request, response, 0);
                error = true;
            }

            if (!error) {
                // Setting up filters, and parse some request headers
                rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
                try {
                    prepareRequest();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    if (getLog().isDebugEnabled()) {
                        getLog().debug(sm.getString(
                                "http11processor.request.prepare"), t);
                    }
                    // 400 - Internal Server Error
                    response.setStatus(400);
                    getAdapter().log(request, response, 0);
                    error = true;
                }
            }

            if (maxKeepAliveRequests == 1) {
                keepAlive = false;
            } else if (maxKeepAliveRequests > 0 &&
                    socketWrapper.decrementKeepAlive() <= 0) {
                keepAlive = false;
            }

            // Process the request in the adapter
            if (!error) {
                try {
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    getAdapter().service(request, response);
                    // Handle when the response was committed before a serious
                    // error occurred.  Throwing a ServletException should both
                    // set the status to 500 and set the errorException.
                    // If we fail here, then the response is likely already
                    // committed, so we can't try and set headers.
                    if(keepAlive && !error) { // Avoid checking twice.
                        error = response.getErrorException() != null ||
                                (!isAsync() &&
                                statusDropsConnection(response.getStatus()));
                    }
                    setCometTimeouts(socketWrapper);
                } catch (InterruptedIOException e) {
                    error = true;
                } catch (HeadersTooLargeException e) {
                    error = true;
                    // The response should not have been committed but check it
                    // anyway to be safe
                    if (!response.isCommitted()) {
                        response.reset();
                        response.setStatus(500);
                        response.setHeader("Connection", "close");
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    getLog().error(sm.getString(
                            "http11processor.request.process"), t);
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    getAdapter().log(request, response, 0);
                    error = true;
                }
            }

            // Finish the handling of the request
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);

            if (!isAsync() && !comet) {
                if (error) {
                    // If we know we are closing the connection, don't drain
                    // input. This way uploading a 100GB file doesn't tie up the
                    // thread if the servlet has rejected it.
                    getInputBuffer().setSwallowInput(false);
                }
                if (response.getStatus() < 200 || response.getStatus() > 299) {
                    if (expectation) {
                        // Client sent Expect: 100-continue but received a
                        // non-2xx response. Disable keep-alive (if enabled) to
                        // ensure the connection is closed. Some clients may
                        // still send the body, some may send the next request.
                        // No way to differentiate, so close the connection to
                        // force the client to send the next request.
                        getInputBuffer().setSwallowInput(false);
                        keepAlive = false;
                    }
                }
                endRequest();
            }

            rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

            // If there was an error, make sure the request is counted as
            // and error, and update the statistics counter
            if (error) {
                response.setStatus(500);
            }
            request.updateCounters();

            if (!isAsync() && !comet || error) {
                getInputBuffer().nextRequest();
                getOutputBuffer().nextRequest();
            }

            if (!disableUploadTimeout) {
                if(endpoint.getSoTimeout() > 0) {
                    setSocketTimeout(endpoint.getSoTimeout());
                } else {
                    setSocketTimeout(0);
                }
            }

            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

            if (breakKeepAliveLoop(socketWrapper)) {
                break;
            }
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (error || endpoint.isPaused()) {
            return SocketState.CLOSED;
        } else if (isAsync() || comet) {
            return SocketState.LONG;
        } else if (isUpgrade()) {
            return SocketState.UPGRADING;
        } else {
            if (sendfileInProgress) {
                return SocketState.SENDFILE;
            } else {
                if (openSocket) {
                    if (readComplete) {
                        return SocketState.OPEN;
                    } else {
                        return SocketState.LONG;
                    }
                } else {
                    return SocketState.CLOSED;
                }
            }
        }
    }


    /**
     * After reading the request headers, we have to setup the request filters.
     */
    protected void prepareRequest() {

        http11 = true;
        http09 = false;
        contentDelimitation = false;
        expectation = false;

        prepareRequestInternal();

        if (endpoint.isSSLEnabled()) {
            request.scheme().setString("https");
        }
        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11)) {
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10)) {
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals("")) {
            // HTTP/0.9
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else {
            // Unsupported protocol
            http11 = false;
            error = true;
            // Send 505; Unsupported HTTP version
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("http11processor.request.prepare")+
                          " Unsupported HTTP version \""+protocolMB+"\"");
            }
            response.setStatus(505);
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals(Constants.GET)) {
            methodMB.setString(Constants.GET);
        } else if (methodMB.equals(Constants.POST)) {
            methodMB.setString(Constants.POST);
        }

        MimeHeaders headers = request.getMimeHeaders();

        // Check connection header
        MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
        if (connectionValueMB != null) {
            ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
            if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1) {
                keepAlive = false;
            } else if (findBytes(connectionValueBC,
                                 Constants.KEEPALIVE_BYTES) != -1) {
                keepAlive = true;
            }
        }

        MessageBytes expectMB = null;
        if (http11) {
            expectMB = headers.getValue("expect");
        }
        if ((expectMB != null)
            && (expectMB.indexOfIgnoreCase("100-continue", 0) != -1)) {
            getInputBuffer().setSwallowInput(false);
            expectation = true;
        }

        // Check user-agent header
        if ((restrictedUserAgents != null) && ((http11) || (keepAlive))) {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            // Check in the restricted list, and adjust the http11
            // and keepAlive flags accordingly
            if(userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();
                if (restrictedUserAgents != null &&
                        restrictedUserAgents.matcher(userAgentValue).matches()) {
                    http11 = false;
                    keepAlive = false;
                }
            }
        }

        // Check for a full URI (including protocol://host:port/)
        ByteChunk uriBC = request.requestURI().getByteChunk();
        if (uriBC.startsWithIgnoreCase("http", 0)) {

            int pos = uriBC.indexOf("://", 0, 3, 4);
            int uriBCStart = uriBC.getStart();
            int slashPos = -1;
            if (pos != -1) {
                byte[] uriB = uriBC.getBytes();
                slashPos = uriBC.indexOf('/', pos + 3);
                if (slashPos == -1) {
                    slashPos = uriBC.getLength();
                    // Set URI as "/"
                    request.requestURI().setBytes
                        (uriB, uriBCStart + pos + 1, 1);
                } else {
                    request.requestURI().setBytes
                        (uriB, uriBCStart + slashPos,
                         uriBC.getLength() - slashPos);
                }
                MessageBytes hostMB = headers.setValue("host");
                hostMB.setBytes(uriB, uriBCStart + pos + 3,
                                slashPos - pos - 3);
            }

        }

        // Input filter setup
        InputFilter[] inputFilters = getInputBuffer().getFilters();

        // Parse transfer-encoding header
        MessageBytes transferEncodingValueMB = null;
        if (http11) {
            transferEncodingValueMB = headers.getValue("transfer-encoding");
        }
        if (transferEncodingValueMB != null) {
            String transferEncodingValue = transferEncodingValueMB.toString();
            // Parse the comma separated list. "identity" codings are ignored
            int startPos = 0;
            int commaPos = transferEncodingValue.indexOf(',');
            String encodingName = null;
            while (commaPos != -1) {
                encodingName = transferEncodingValue.substring
                    (startPos, commaPos).toLowerCase(Locale.ENGLISH).trim();
                if (!addInputFilter(inputFilters, encodingName)) {
                    // Unsupported transfer encoding
                    error = true;
                    // 501 - Unimplemented
                    response.setStatus(501);
                }
                startPos = commaPos + 1;
                commaPos = transferEncodingValue.indexOf(',', startPos);
            }
            encodingName = transferEncodingValue.substring(startPos)
                .toLowerCase(Locale.ENGLISH).trim();
            if (!addInputFilter(inputFilters, encodingName)) {
                // Unsupported transfer encoding
                error = true;
                // 501 - Unimplemented
                if (getLog().isDebugEnabled()) {
                    getLog().debug(sm.getString("http11processor.request.prepare")+
                              " Unsupported transfer encoding \""+encodingName+"\"");
                }
                response.setStatus(501);
            }
        }

        // Parse content-length header
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0) {
            if (contentDelimitation) {
                // contentDelimitation being true at this point indicates that
                // chunked encoding is being used but chunked encoding should
                // not be used with a content length. RFC 2616, section 4.4,
                // bullet 3 states Content-Length must be ignored in this case -
                // so remove it.
                headers.removeHeader("content-length");
                request.setContentLength(-1);
            } else {
                getInputBuffer().addActiveFilter
                        (inputFilters[Constants.IDENTITY_FILTER]);
                contentDelimitation = true;
            }
        }

        MessageBytes valueMB = headers.getValue("host");

        // Check host header
        if (http11 && (valueMB == null)) {
            error = true;
            // 400 - Bad request
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("http11processor.request.prepare")+
                          " host header missing");
            }
            response.setStatus(400);
        }

        parseHost(valueMB);

        if (!contentDelimitation) {
            // If there's no content length
            // (broken HTTP/1.0 or HTTP/1.1), assume
            // the client is not broken and didn't send a body
            getInputBuffer().addActiveFilter
                    (inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        if (error) {
            getAdapter().log(request, response, 0);
        }
    }


    /**
     * Connector implementation specific request preparation. Ideally, this will
     * go away in the future.
     */
    protected abstract void prepareRequestInternal();

    /**
     * When committing the response, we have to validate the set of headers, as
     * well as setup the response filters.
     */
    private void prepareResponse() {

        boolean entityBody = true;
        contentDelimitation = false;

        OutputFilter[] outputFilters = getOutputBuffer().getFilters();

        if (http09 == true) {
            // HTTP/0.9
            getOutputBuffer().addActiveFilter
                (outputFilters[Constants.IDENTITY_FILTER]);
            return;
        }

        int statusCode = response.getStatus();
        if (statusCode < 200 || statusCode == 204 || statusCode == 205 ||
                statusCode == 304) {
            // No entity body
            getOutputBuffer().addActiveFilter
                (outputFilters[Constants.VOID_FILTER]);
            entityBody = false;
            contentDelimitation = true;
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals("HEAD")) {
            // No entity body
            getOutputBuffer().addActiveFilter
                (outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Sendfile support
        boolean sendingWithSendfile = false;
        if (getEndpoint().getUseSendfile()) {
            sendingWithSendfile = prepareSendfile(outputFilters);
        }

        // Check for compression
        boolean isCompressable = false;
        boolean useCompression = false;
        if (entityBody && (compressionLevel > 0) && !sendingWithSendfile) {
            isCompressable = isCompressable();
            if (isCompressable) {
                useCompression = useCompression();
            }
            // Change content-length to -1 to force chunking
            if (useCompression) {
                response.setContentLength(-1);
            }
        }

        MimeHeaders headers = response.getMimeHeaders();
        if (!entityBody) {
            response.setContentLength(-1);
        }
        // A SC_NO_CONTENT response may include entity headers
        if (entityBody || statusCode == HttpServletResponse.SC_NO_CONTENT) {
            String contentType = response.getContentType();
            if (contentType != null) {
                headers.setValue("Content-Type").setString(contentType);
            }
            String contentLanguage = response.getContentLanguage();
            if (contentLanguage != null) {
                headers.setValue("Content-Language")
                    .setString(contentLanguage);
            }
        }

        long contentLength = response.getContentLengthLong();
        boolean connectionClosePresent = false;
        if (contentLength != -1) {
            headers.setValue("Content-Length").setLong(contentLength);
            getOutputBuffer().addActiveFilter
                (outputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        } else {
            // If the response code supports an entity body and we're on
            // HTTP 1.1 then we chunk unless we have a Connection: close header
            connectionClosePresent = isConnectionClose(headers);
            if (entityBody && http11 && !connectionClosePresent) {
                getOutputBuffer().addActiveFilter
                    (outputFilters[Constants.CHUNKED_FILTER]);
                contentDelimitation = true;
                headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
            } else {
                getOutputBuffer().addActiveFilter
                    (outputFilters[Constants.IDENTITY_FILTER]);
            }
        }

        if (useCompression) {
            getOutputBuffer().addActiveFilter(outputFilters[Constants.GZIP_FILTER]);
            headers.setValue("Content-Encoding").setString("gzip");
        }
        // If it might be compressed, set the Vary header
        if (isCompressable) {
            // Make Proxies happy via Vary (from mod_deflate)
            MessageBytes vary = headers.getValue("Vary");
            if (vary == null) {
                // Add a new Vary header
                headers.setValue("Vary").setString("Accept-Encoding");
            } else if (vary.equals("*")) {
                // No action required
            } else {
                // Merge into current header
                headers.setValue("Vary").setString(
                        vary.getString() + ",Accept-Encoding");
            }
        }

        // Add date header unless application has already set one (e.g. in a
        // Caching Filter)
        if (headers.getValue("Date") == null) {
            headers.setValue("Date").setString(
                    FastHttpDateFormat.getCurrentDate());
        }

        // FIXME: Add transfer encoding header

        if ((entityBody) && (!contentDelimitation)) {
            // Mark as close the connection after the request, and add the
            // connection: close header
            keepAlive = false;
        }

        // If we know that the request is bad this early, add the
        // Connection: close header.
        keepAlive = keepAlive && !statusDropsConnection(statusCode);
        if (!keepAlive) {
            // Avoid adding the close header twice
            if (!connectionClosePresent) {
                headers.addValue(Constants.CONNECTION).setString(
                        Constants.CLOSE);
            }
        } else if (!http11 && !error) {
            headers.addValue(Constants.CONNECTION).setString(Constants.KEEPALIVE);
        }

        // Build the response header
        getOutputBuffer().sendStatus();

        // Add server header
        if (server != null) {
            // Always overrides anything the app might set
            headers.setValue("Server").setString(server);
        } else if (headers.getValue("Server") == null) {
            // If app didn't set the header, use the default
            getOutputBuffer().write(Constants.SERVER_BYTES);
        }

        int size = headers.size();
        for (int i = 0; i < size; i++) {
            getOutputBuffer().sendHeader(headers.getName(i), headers.getValue(i));
        }
        getOutputBuffer().endHeaders();

    }

    private boolean isConnectionClose(MimeHeaders headers) {
        MessageBytes connection = headers.getValue(Constants.CONNECTION);
        if (connection == null) {
            return false;
        }
        return connection.equals(Constants.CLOSE);
    }

    abstract boolean prepareSendfile(OutputFilter[] outputFilters);

    /**
     * Parse host.
     */
    protected void parseHost(MessageBytes valueMB) {

        if (valueMB == null || valueMB.isNull()) {
            // HTTP/1.0
            // If no host header, use the port info from the endpoint
            // The host will be obtained lazily from the socket if required
            // using ActionCode#REQ_LOCAL_NAME_ATTRIBUTE
            request.setServerPort(endpoint.getPort());
            return;
        }

        ByteChunk valueBC = valueMB.getByteChunk();
        byte[] valueB = valueBC.getBytes();
        int valueL = valueBC.getLength();
        int valueS = valueBC.getStart();
        int colonPos = -1;
        if (hostNameC.length < valueL) {
            hostNameC = new char[valueL];
        }

        boolean ipv6 = (valueB[valueS] == '[');
        boolean bracketClosed = false;
        for (int i = 0; i < valueL; i++) {
            char b = (char) valueB[i + valueS];
            hostNameC[i] = b;
            if (b == ']') {
                bracketClosed = true;
            } else if (b == ':') {
                if (!ipv6 || bracketClosed) {
                    colonPos = i;
                    break;
                }
            }
        }

        if (colonPos < 0) {
            if (!endpoint.isSSLEnabled()) {
                // 80 - Default HTTP port
                request.setServerPort(80);
            } else {
                // 443 - Default HTTPS port
                request.setServerPort(443);
            }
            request.serverName().setChars(hostNameC, 0, valueL);
        } else {
            request.serverName().setChars(hostNameC, 0, colonPos);

            int port = 0;
            int mult = 1;
            for (int i = valueL - 1; i > colonPos; i--) {
                int charValue = HexUtils.getDec(valueB[i + valueS]);
                if (charValue == -1 || charValue > 9) {
                    // Invalid character
                    error = true;
                    // 400 - Bad request
                    response.setStatus(400);
                    break;
                }
                port = port + (charValue * mult);
                mult = 10 * mult;
            }
            request.setServerPort(port);
        }

    }


    @Override
    public SocketState asyncDispatch(SocketStatus status) {

        if (status == SocketStatus.OPEN_WRITE) {
            try {
                asyncStateMachine.asyncOperation();
                try {
                    if (outputBuffer.hasDataToWrite()) {
                        if (outputBuffer.flushBuffer(false)) {
                            // There is data to write but go via Response to
                            // maintain a consistent view of non-blocking state
                            response.checkRegisterForWrite(true);
                            return SocketState.LONG;
                        }
                    }
                } catch (IOException x) {
                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Unable to write async data.",x);
                    }
                    status = SocketStatus.ASYNC_WRITE_ERROR;
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, x);
                }
            } catch (IllegalStateException x) {
                registerForEvent(false, true);
            }
        } else if (status == SocketStatus.OPEN_READ &&
                request.getReadListener() != null) {
            try {
                if (inputBuffer.available() > 0) {
                    asyncStateMachine.asyncOperation();
                }
            } catch (IllegalStateException x) {
                registerForEvent(true, false);
            }
        }

        RequestInfo rp = request.getRequestProcessor();
        try {
            rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
            error = !getAdapter().asyncDispatch(request, response, status);
            resetTimeouts();
        } catch (InterruptedIOException e) {
            error = true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            getLog().error(sm.getString("http11processor.request.process"), t);
            error = true;
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (error) {
            return SocketState.CLOSED;
        } else if (isAsync()) {
            return SocketState.LONG;
        } else {
            if (!keepAlive) {
                return SocketState.CLOSED;
            } else {
                getInputBuffer().nextRequest();
                getOutputBuffer().nextRequest();
                return SocketState.OPEN;
            }
        }
    }


    @Override
    public boolean isComet() {
        return comet;
    }


    @Override
    public boolean isUpgrade() {
        return httpUpgradeHandler != null;
    }



    @Override
    public SocketState upgradeDispatch(SocketStatus status) throws IOException {
        // Should never reach this code but in case we do...
        throw new IllegalStateException(
                sm.getString("http11Processor.upgrade"));
    }


    @Override
    public HttpUpgradeHandler getHttpUpgradeHandler() {
        return httpUpgradeHandler;
    }


    /**
     * Provides a mechanism for those connector implementations (currently only
     * NIO) that need to reset timeouts from Async timeouts to standard HTTP
     * timeouts once async processing completes.
     */
    protected abstract void resetTimeouts();


    /**
     * Provides a mechanism for those connectors (currently only NIO) that need
     * that need to set comet timeouts.
     */
    protected abstract void setCometTimeouts(SocketWrapper<S> socketWrapper);

    public void endRequest() {

        // Finish the handling of the request
        try {
            getInputBuffer().endRequest();
        } catch (IOException e) {
            error = true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            getLog().error(sm.getString("http11processor.request.finish"), t);
            // 500 - Internal Server Error
            // Can't add a 500 to the access log since that has already been
            // written in the Adapter.service method.
            response.setStatus(500);
            error = true;
        }
        try {
            getOutputBuffer().endRequest();
        } catch (IOException e) {
            error = true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            getLog().error(sm.getString("http11processor.response.finish"), t);
            error = true;
        }

    }


    /**
     * Checks to see if the keep-alive loop should be broken, performing any
     * processing (e.g. sendfile handling) that may have an impact on whether
     * or not the keep-alive loop should be broken.
     * @return true if the keep-alive loop should be broken
     */
    protected abstract boolean breakKeepAliveLoop(
            SocketWrapper<S> socketWrapper);



    @Override
    public final void recycle(boolean isSocketClosing) {
        if (getInputBuffer() != null) {
            getInputBuffer().recycle();
        }
        if (getOutputBuffer() != null) {
            getOutputBuffer().recycle();
        }
        if (asyncStateMachine != null) {
            asyncStateMachine.recycle();
        }
        httpUpgradeHandler = null;
        comet = false;
        recycleInternal();
    }

    protected abstract void recycleInternal();
}
