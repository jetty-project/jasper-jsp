/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.websocket.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.ServerEndpointConfig;

import org.apache.tomcat.util.codec.binary.Base64;
import org.apache.tomcat.websocket.Constants;
import org.apache.tomcat.websocket.WsHandshakeResponse;
import org.apache.tomcat.websocket.pojo.PojoEndpointServer;

public class UpgradeUtil {

    private static final byte[] WS_ACCEPT =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(
                    StandardCharsets.ISO_8859_1);
    private static final Queue<MessageDigest> sha1Helpers =
            new ConcurrentLinkedQueue<>();

    private UpgradeUtil() {
        // Utility class. Hide default constructor.
    }

    /**
     * Checks to see if this is an HTTP request that includes a valid upgrade
     * request to web socket.
     * <p>
     * Note: RFC 2616 does not limit HTTP upgrade to GET requests but the Java
     *       WebSocket spec 1.0, section 8.2 implies such a limitation and RFC
     *       6455 section 4.1 requires that a WebSocket Upgrade uses GET.
     */
    public static boolean isWebSocketUpgradeRequest(ServletRequest request,
            ServletResponse response) {

        return ((request instanceof HttpServletRequest) &&
                (response instanceof HttpServletResponse) &&
                headerContainsToken((HttpServletRequest) request,
                        Constants.UPGRADE_HEADER_NAME,
                        Constants.UPGRADE_HEADER_VALUE) &&
                "GET".equals(((HttpServletRequest) request).getMethod()));
    }


    public static void doUpgrade(WsServerContainer sc, HttpServletRequest req,
            HttpServletResponse resp, ServerEndpointConfig sec,
            Map<String,String> pathParams)
            throws ServletException, IOException {

        // Validate the rest of the headers and reject the request if that
        // validation fails
        String key;
        String subProtocol = null;
        List<Extension> extensions = Collections.emptyList();
        if (!headerContainsToken(req, Constants.CONNECTION_HEADER_NAME,
                Constants.CONNECTION_HEADER_VALUE)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (!headerContainsToken(req, Constants.WS_VERSION_HEADER_NAME,
                Constants.WS_VERSION_HEADER_VALUE)) {
            resp.setStatus(426);
            resp.setHeader(Constants.WS_VERSION_HEADER_NAME,
                    Constants.WS_VERSION_HEADER_VALUE);
            return;
        }
        key = req.getHeader(Constants.WS_KEY_HEADER_NAME);
        if (key == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }


        // Origin check
        String origin = req.getHeader("Origin");
        if (!sec.getConfigurator().checkOrigin(origin)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        // Sub-protocols
        List<String> subProtocols = getTokensFromHeader(req,
                "Sec-WebSocket-Protocol");
        subProtocol = sec.getConfigurator().getNegotiatedSubprotocol(
                sec.getSubprotocols(), subProtocols);

        // Extensions
        // Currently no extensions are supported by this implementation

        // If we got this far, all is good. Accept the connection.
        resp.setHeader(Constants.UPGRADE_HEADER_NAME,
                Constants.UPGRADE_HEADER_VALUE);
        resp.setHeader(Constants.CONNECTION_HEADER_NAME,
                Constants.CONNECTION_HEADER_VALUE);
        resp.setHeader(HandshakeResponse.SEC_WEBSOCKET_ACCEPT,
                getWebSocketAccept(key));
        if (subProtocol != null && subProtocol.length() > 0) {
            // RFC6455 4.2.2 explicitly states "" is not valid here
            resp.setHeader("Sec-WebSocket-Protocol", subProtocol);
        }
        if (!extensions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            Iterator<Extension> iter = extensions.iterator();
            // There must be at least one
            sb.append(iter.next());
            while (iter.hasNext()) {
                sb.append(',');
                sb.append(iter.next().getName());
            }
            resp.setHeader("Sec-WebSocket-Extensions", sb.toString());
        }

        WsHandshakeRequest wsRequest = new WsHandshakeRequest(req);
        WsHandshakeResponse wsResponse = new WsHandshakeResponse();
        WsPerSessionServerEndpointConfig perSessionServerEndpointConfig =
                new WsPerSessionServerEndpointConfig(sec);
        sec.getConfigurator().modifyHandshake(perSessionServerEndpointConfig,
                wsRequest, wsResponse);
        wsRequest.finished();

        // Add any additional headers
        for (Entry<String,List<String>> entry :
                wsResponse.getHeaders().entrySet()) {
            for (String headerValue: entry.getValue()) {
                resp.addHeader(entry.getKey(), headerValue);
            }
        }

        Endpoint ep;
        try {
            Class<?> clazz = sec.getEndpointClass();
            if (Endpoint.class.isAssignableFrom(clazz)) {
                ep = (Endpoint) sec.getConfigurator().getEndpointInstance(
                        clazz);
            } else {
                ep = new PojoEndpointServer();
            }
        } catch (InstantiationException e) {
            throw new ServletException(e);
        }

        WsHttpUpgradeHandler wsHandler =
                req.upgrade(WsHttpUpgradeHandler.class);
        wsHandler.preInit(ep, perSessionServerEndpointConfig, sc, wsRequest,
                subProtocol, pathParams, req.isSecure());

    }


    /*
     * This only works for tokens. Quoted strings need more sophisticated
     * parsing.
     */
    private static boolean headerContainsToken(HttpServletRequest req,
            String headerName, String target) {
        Enumeration<String> headers = req.getHeaders(headerName);
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            String[] tokens = header.split(",");
            for (String token : tokens) {
                if (target.equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }


    /*
     * This only works for tokens. Quoted strings need more sophisticated
     * parsing.
     */
    private static List<String> getTokensFromHeader(HttpServletRequest req,
            String headerName) {
        List<String> result = new ArrayList<>();
        Enumeration<String> headers = req.getHeaders(headerName);
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            String[] tokens = header.split(",");
            for (String token : tokens) {
                result.add(token.trim());
            }
        }
        return result;
    }


    private static String getWebSocketAccept(String key) throws ServletException {
        MessageDigest sha1Helper = sha1Helpers.poll();
        if (sha1Helper == null) {
            try {
                sha1Helper = MessageDigest.getInstance("SHA1");
            } catch (NoSuchAlgorithmException e) {
                throw new ServletException(e);
            }
        }
        sha1Helper.reset();
        sha1Helper.update(key.getBytes(StandardCharsets.ISO_8859_1));
        String result = Base64.encodeBase64String(sha1Helper.digest(WS_ACCEPT));
        sha1Helpers.add(sha1Helper);
        return result;
    }
}
