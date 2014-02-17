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
package org.apache.catalina.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.authenticator.BasicAuthenticator;
import org.apache.catalina.filters.FailedRequestFilter;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.TesterMapRealm;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.descriptor.web.LoginConfig;

/**
 * Test case for {@link Request}.
 */
public class TestRequest extends TomcatBaseTest {

    /**
     * Test case for https://issues.apache.org/bugzilla/show_bug.cgi?id=37794
     * POST parameters are not returned from a call to
     * any of the {@link HttpServletRequest} getParameterXXX() methods if the
     * request is chunked.
     */
    @Test
    public void testBug37794() {
        Bug37794Client client = new Bug37794Client(true);

        // Edge cases around zero
        client.doRequest(-1, false); // Unlimited
        assertTrue(client.isResponse200());
        assertTrue(client.isResponseBodyOK());
        client.reset();
        client.doRequest(0, false); // Unlimited
        assertTrue(client.isResponse200());
        assertTrue(client.isResponseBodyOK());
        client.reset();
        client.doRequest(1, false); // 1 byte - too small should fail
        assertTrue(client.isResponse400());

        client.reset();

        // Edge cases around actual content length
        client.reset();
        client.doRequest(6, false); // Too small should fail
        assertTrue(client.isResponse400());
        client.reset();
        client.doRequest(7, false); // Just enough should pass
        assertTrue(client.isResponse200());
        assertTrue(client.isResponseBodyOK());
        client.reset();
        client.doRequest(8, false); // 1 extra - should pass
        assertTrue(client.isResponse200());
        assertTrue(client.isResponseBodyOK());

        // Much larger
        client.reset();
        client.doRequest(8096, false); // Plenty of space - should pass
        assertTrue(client.isResponse200());
        assertTrue(client.isResponseBodyOK());

        // Check for case insensitivity
        client.reset();
        client.doRequest(8096, true); // Plenty of space - should pass
        assertTrue(client.isResponse200());
        assertTrue(client.isResponseBodyOK());
    }

    /**
     * Additional test for failed requests handling when no FailedRequestFilter
     * is defined.
     */
    @Test
    public void testBug37794withoutFilter() {
        Bug37794Client client = new Bug37794Client(false);

        // Edge cases around actual content length
        client.reset();
        client.doRequest(6, false); // Too small should fail
        // Response code will be OK, but parameters list will be empty
        assertTrue(client.isResponse200());
        assertEquals("", client.getResponseBody());
    }

    private static class Bug37794Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        /**
         * Only interested in the parameters and values for POST requests.
         */
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Just echo the parameters and values back as plain text
            resp.setContentType("text/plain");

            PrintWriter out = resp.getWriter();

            // Assume one value per attribute
            Enumeration<String> names = req.getParameterNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                out.println(name + "=" + req.getParameter(name));
            }
        }
    }

    /**
     * Bug 37794 test client.
     */
    private class Bug37794Client extends SimpleHttpClient {

        private final boolean createFilter;

        private boolean init;

        public Bug37794Client(boolean createFilter) {
            this.createFilter = createFilter;
        }

        private synchronized void init() throws Exception {
            if (init) return;

            Tomcat tomcat = getTomcatInstance();
            Context root = tomcat.addContext("", TEMP_DIR);
            Tomcat.addServlet(root, "Bug37794", new Bug37794Servlet());
            root.addServletMapping("/test", "Bug37794");

            if (createFilter) {
                FilterDef failedRequestFilter = new FilterDef();
                failedRequestFilter.setFilterName("failedRequestFilter");
                failedRequestFilter.setFilterClass(
                        FailedRequestFilter.class.getName());
                FilterMap failedRequestFilterMap = new FilterMap();
                failedRequestFilterMap.setFilterName("failedRequestFilter");
                failedRequestFilterMap.addURLPattern("/*");
                root.addFilterDef(failedRequestFilter);
                root.addFilterMap(failedRequestFilterMap);
            }

            tomcat.start();

            setPort(tomcat.getConnector().getLocalPort());

            init = true;
        }

        private Exception doRequest(int postLimit, boolean ucChunkedHead) {
            Tomcat tomcat = getTomcatInstance();

            try {
                init();
                tomcat.getConnector().setMaxPostSize(postLimit);

                // Open connection
                connect();

                // Send request in two parts
                String[] request = new String[2];
                if (ucChunkedHead) {
                    request[0] =
                        "POST http://localhost:8080/test HTTP/1.1" + CRLF +
                        "content-type: application/x-www-form-urlencoded" + CRLF +
                        "Transfer-Encoding: CHUNKED" + CRLF +
                        "Connection: close" + CRLF +
                        CRLF +
                        "3" + CRLF +
                        "a=1" + CRLF;
                } else {
                    request[0] =
                        "POST http://localhost:8080/test HTTP/1.1" + CRLF +
                        "content-type: application/x-www-form-urlencoded" + CRLF +
                        "Transfer-Encoding: chunked" + CRLF +
                        "Connection: close" + CRLF +
                        CRLF +
                        "3" + CRLF +
                        "a=1" + CRLF;
                }
                request[1] =
                    "4" + CRLF +
                    "&b=2" + CRLF +
                    "0" + CRLF +
                    CRLF;

                setRequest(request);
                processRequest(); // blocks until response has been read

                // Close the connection
                disconnect();
            } catch (Exception e) {
                return e;
            }
            return null;
        }

        @Override
        public boolean isResponseBodyOK() {
            if (getResponseBody() == null) {
                return false;
            }
            if (!getResponseBody().contains("a=1")) {
                return false;
            }
            if (!getResponseBody().contains("b=2")) {
                return false;
            }
            return true;
        }

    }

    /**
     * Test case for
     * <a href="https://issues.apache.org/bugzilla/show_bug.cgi?id=38113">bug
     * 38118</a>.
     */
    @Test
    public void testBug38113() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctx =
            tomcat.addContext("", System.getProperty("java.io.tmpdir"));

        // Add the Servlet
        Tomcat.addServlet(ctx, "servlet", new EchoQueryStringServlet());
        ctx.addServletMapping("/", "servlet");

        tomcat.start();

        // No query string
        ByteChunk res = getUrl("http://localhost:" + getPort() + "/");
        assertEquals("QueryString=null", res.toString());

        // Query string
        res = getUrl("http://localhost:" + getPort() + "/?a=b");
        assertEquals("QueryString=a=b", res.toString());

        // Empty string
        res = getUrl("http://localhost:" + getPort() + "/?");
        assertEquals("QueryString=", res.toString());
    }

    private static final class EchoQueryStringServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            PrintWriter pw = resp.getWriter();
            pw.print("QueryString=" + req.getQueryString());
        }
    }

    /**
     * Test case for {@link Request#login(String, String)} and
     * {@link Request#logout()}.
     */
    @Test
    public void testLoginLogout() throws Exception{
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctx =
            tomcat.addContext("", System.getProperty("java.io.tmpdir"));

        LoginConfig config = new LoginConfig();
        config.setAuthMethod("BASIC");
        ctx.setLoginConfig(config);
        ctx.getPipeline().addValve(new BasicAuthenticator());

        Tomcat.addServlet(ctx, "servlet", new LoginLogoutServlet());
        ctx.addServletMapping("/", "servlet");

        TesterMapRealm realm = new TesterMapRealm();
        realm.addUser(LoginLogoutServlet.USER, LoginLogoutServlet.PWD);
        ctx.setRealm(realm);

        tomcat.start();

        ByteChunk res = getUrl("http://localhost:" + getPort() + "/");
        assertEquals(LoginLogoutServlet.OK, res.toString());
    }

    private static final class LoginLogoutServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        private static final String USER = "user";
        private static final String PWD = "pwd";
        private static final String OK = "OK";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            req.login(USER, PWD);

            if (!req.getRemoteUser().equals(USER))
                throw new ServletException();
            if (!req.getUserPrincipal().getName().equals(USER))
                throw new ServletException();

            req.logout();

            if (req.getRemoteUser() != null)
                throw new ServletException();
            if (req.getUserPrincipal() != null)
                throw new ServletException();

            resp.getWriter().write(OK);
        }

    }

    @Test
    public void testBug49424NoChunking() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context root = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(root, "Bug37794", new Bug37794Servlet());
        root.addServletMapping("/", "Bug37794");
        tomcat.start();

        HttpURLConnection conn = getConnection("http://localhost:" + getPort() + "/");
        InputStream is = conn.getInputStream();
        assertNotNull(is);
    }

    @Test
    public void testBug49424WithChunking() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context root = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(root, "Bug37794", new Bug37794Servlet());
        root.addServletMapping("/", "Bug37794");
        tomcat.start();

        HttpURLConnection conn = getConnection("http://localhost:" + getPort() + "/");
        conn.setChunkedStreamingMode(8 * 1024);
        InputStream is = conn.getInputStream();
        assertNotNull(is);
    }

    /**
     * Test case for https://issues.apache.org/bugzilla/show_bug.cgi?id=48692
     * PUT requests should be able to fetch request parameters coming from
     * the request body (when properly configured using the new parseBodyMethod
     * setting).
     */
    @Test
    public void testBug48692() {
        Bug48692Client client = new Bug48692Client();

        // Make sure GET works properly
        client.doRequest("GET", "foo=bar", null, null, false);

        assertTrue("Non-200 response for GET request",
                   client.isResponse200());
        assertEquals("Incorrect response for GET request",
                     "foo=bar",
                     client.getResponseBody());

        client.reset();

        //
        // Make sure POST works properly
        //
        // POST with separate GET and POST parameters
        client.doRequest("POST", "foo=bar", "application/x-www-form-urlencoded", "bar=baz", true);

        assertTrue("Non-200 response for POST request",
                   client.isResponse200());
        assertEquals("Incorrect response for POST request",
                     "bar=baz,foo=bar",
                     client.getResponseBody());

        client.reset();

        // POST with overlapping GET and POST parameters
        client.doRequest("POST", "foo=bar&bar=foo", "application/x-www-form-urlencoded", "bar=baz&foo=baz", true);

        assertTrue("Non-200 response for POST request",
                   client.isResponse200());
        assertEquals("Incorrect response for POST request",
                     "bar=baz,bar=foo,foo=bar,foo=baz",
                     client.getResponseBody());

        client.reset();

        // PUT without POST-style parsing
        client.doRequest("PUT", "foo=bar&bar=foo", "application/x-www-form-urlencoded", "bar=baz&foo=baz", false);

        assertTrue("Non-200 response for PUT/noparse request",
                   client.isResponse200());
        assertEquals("Incorrect response for PUT request",
                     "bar=foo,foo=bar",
                     client.getResponseBody());

        client.reset();

        // PUT with POST-style parsing
        client.doRequest("PUT", "foo=bar&bar=foo", "application/x-www-form-urlencoded", "bar=baz&foo=baz", true);

        assertTrue("Non-200 response for PUT request",
                   client.isResponse200());
        assertEquals("Incorrect response for PUT/parse request",
                     "bar=baz,bar=foo,foo=bar,foo=baz",
                     client.getResponseBody());

        client.reset();

        /*
        private Exception doRequest(String method,
                                    String queryString,
                                    String contentType,
                                    String requestBody,
                                    boolean allowBody) {
        */
    }

    @Test
    public void testBug54984() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context root = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));
        root.setAllowCasualMultipartParsing(true);
        Tomcat.addServlet(root, "Bug54984", new Bug54984Servlet());
        root.addServletMapping("/", "Bug54984");
        tomcat.start();

        HttpURLConnection conn = getConnection("http://localhost:" + getPort()
                + "/parseParametersBeforeParseParts");

        prepareRequestBug54984(conn);

        checkResponseBug54984(conn);

        conn.disconnect();

        conn = getConnection("http://localhost:" + getPort() + "/");

        prepareRequestBug54984(conn);

        checkResponseBug54984(conn);

        conn.disconnect();
    }

    /**
     *
     */
    private static class EchoParametersServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        /**
         * Only interested in the parameters and values for requests.
         * Note: echos parameters in alphabetical order.
         */
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
            // Just echo the parameters and values back as plain text
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");

            PrintWriter out = resp.getWriter();

            TreeMap<String,String[]> parameters =
                    new TreeMap<>(req.getParameterMap());

            boolean first = true;

            for(String name: parameters.keySet()) {
                String[] values = req.getParameterValues(name);

                java.util.Arrays.sort(values);

                for(int i=0; i<values.length; ++i)
                {
                    if(first)
                        first = false;
                    else
                        out.print(",");

                    out.print(name + "=" + values[i]);
                }
            }
        }
    }

    /**
     * Bug 48692 test client: test for allowing PUT request bodies.
     */
    private class Bug48692Client extends SimpleHttpClient {

        private boolean init;

        private synchronized void init() throws Exception {
            if (init) return;

            Tomcat tomcat = getTomcatInstance();
            Context root = tomcat.addContext("", TEMP_DIR);
            Tomcat.addServlet(root, "EchoParameters", new EchoParametersServlet());
            root.addServletMapping("/echo", "EchoParameters");
            tomcat.start();

            setPort(tomcat.getConnector().getLocalPort());

            init = true;
        }

        private Exception doRequest(String method,
                                    String queryString,
                                    String contentType,
                                    String requestBody,
                                    boolean allowBody) {
            Tomcat tomcat = getTomcatInstance();

            try {
                init();
                if(allowBody)
                    tomcat.getConnector().setParseBodyMethods(method);
                else
                    tomcat.getConnector().setParseBodyMethods(""); // never parse

                // Open connection
                connect();

                // Re-encode the request body so that bytes = characters
                if(null != requestBody)
                    requestBody = new String(requestBody.getBytes("UTF-8"), "ASCII");

                // Send specified request body using method
                String[] request = {
                    (
                     method + " http://localhost:" + getPort() + "/echo"
                     + (null == queryString ? "" : ("?" + queryString))
                     + " HTTP/1.1" + CRLF
                     + "Host: localhost" + CRLF
                     + (null == contentType ? ""
                        : ("Content-Type: " + contentType + CRLF))
                     + "Connection: close" + CRLF
                     + (null == requestBody ? "" : "Content-Length: " + requestBody.length() + CRLF)
                     + CRLF
                     + (null == requestBody ? "" : requestBody)
                     )
                };

                setRequest(request);
                processRequest(); // blocks until response has been read

                // Close the connection
                disconnect();
            } catch (Exception e) {
                return e;
            }
            return null;
        }

        @Override
        public boolean isResponseBodyOK() {
            return false; // Don't care
        }
    }

    private HttpURLConnection getConnection(String query) throws IOException {
        URL postURL;
        postURL = new URL(query);
        HttpURLConnection conn = (HttpURLConnection) postURL.openConnection();
        conn.setRequestMethod("POST");

        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setAllowUserInteraction(false);

        return conn;
    }

    private static class Bug54984Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.setCharacterEncoding("UTF-8");

            if (req.getRequestURI().endsWith("parseParametersBeforeParseParts")) {
                req.getParameterNames();
            }

            req.getPart("part");

            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");

            resp.getWriter().println("Part " + req.getParameter("part"));
        }
    }

    private void prepareRequestBug54984(HttpURLConnection conn)
            throws Exception {
        String boundary = "-----" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + boundary);

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new OutputStreamWriter(
                    conn.getOutputStream(), "UTF-8"), true);
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"part\"\r\n");
            writer.append("Content-Type: text/plain; charset=UTF-8\r\n");
            writer.append("\r\n");
            writer.append("��").append("\r\n");
            writer.flush();

            writer.append("\r\n");
            writer.flush();

            writer.append("--" + boundary + "--").append("\r\n");
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void checkResponseBug54984(HttpURLConnection conn)
            throws Exception {
        List<String> response = new ArrayList<>();
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_OK) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), "UTF-8"));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    response.add(line);
                }
                assertTrue(response.contains("Part ��"));
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
        } else {
            fail("OK status was expected: " + status);
        }
    }
}
