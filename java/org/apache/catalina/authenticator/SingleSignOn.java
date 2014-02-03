/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.catalina.authenticator;


import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.SessionEvent;
import org.apache.catalina.SessionListener;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;


/**
 * A <strong>Valve</strong> that supports a "single sign on" user experience,
 * where the security identity of a user who successfully authenticates to one
 * web application is propagated to other web applications in the same
 * security domain.  For successful use, the following requirements must
 * be met:
 * <ul>
 * <li>This Valve must be configured on the Container that represents a
 *     virtual host (typically an implementation of <code>Host</code>).</li>
 * <li>The <code>Realm</code> that contains the shared user and role
 *     information must be configured on the same Container (or a higher
 *     one), and not overridden at the web application level.</li>
 * <li>The web applications themselves must use one of the standard
 *     Authenticators found in the
 *     <code>org.apache.catalina.authenticator</code> package.</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 */
public class SingleSignOn extends ValveBase implements SessionListener {

    //------------------------------------------------------ Constructor
    public SingleSignOn() {
        super(true);
    }

    // ----------------------------------------------------- Instance Variables


    /**
     * The cache of SingleSignOnEntry instances for authenticated Principals,
     * keyed by the cookie value that is used to select them.
     */
    protected final Map<String,SingleSignOnEntry> cache = new HashMap<>();


    /**
     * Indicates whether this valve should require a downstream Authenticator to
     * reauthenticate each request, or if it itself can bind a UserPrincipal
     * and AuthType object to the request.
     */
    private boolean requireReauthentication = false;

    /**
     * The cache of single sign on identifiers, keyed by the Session that is
     * associated with them.
     */
    protected final Map<Session,String> reverse = new HashMap<>();


    /**
     * Optional SSO cookie domain.
     */
    private String cookieDomain;

    // ------------------------------------------------------------- Properties

    /**
     * Returns the optional cookie domain.
     * May return null.
     *
     * @return The cookie domain
     */
    public String getCookieDomain() {
        return cookieDomain;
    }
    /**
     * Sets the domain to be used for sso cookies.
     *
     * @param cookieDomain cookie domain name
     */
    public void setCookieDomain(String cookieDomain) {
        if (cookieDomain != null && cookieDomain.trim().length() == 0) {
            this.cookieDomain = null;
        } else {
            this.cookieDomain = cookieDomain;
        }
    }

    /**
     * Gets whether each request needs to be reauthenticated (by an
     * Authenticator downstream in the pipeline) to the security
     * <code>Realm</code>, or if this Valve can itself bind security info
     * to the request based on the presence of a valid SSO entry without
     * rechecking with the <code>Realm</code..
     *
     * @return  <code>true</code> if it is required that a downstream
     *          Authenticator reauthenticate each request before calls to
     *          <code>HttpServletRequest.setUserPrincipal()</code>
     *          and <code>HttpServletRequest.setAuthType()</code> are made;
     *          <code>false</code> if the <code>Valve</code> can itself make
     *          those calls relying on the presence of a valid SingleSignOn
     *          entry associated with the request.
     *
     * @see #setRequireReauthentication
     */
    public boolean getRequireReauthentication()
    {
        return requireReauthentication;
    }


    /**
     * Sets whether each request needs to be reauthenticated (by an
     * Authenticator downstream in the pipeline) to the security
     * <code>Realm</code>, or if this Valve can itself bind security info
     * to the request, based on the presence of a valid SSO entry, without
     * rechecking with the <code>Realm</code.
     * <p>
     * If this property is <code>false</code> (the default), this
     * <code>Valve</code> will bind a UserPrincipal and AuthType to the request
     * if a valid SSO entry is associated with the request.  It will not notify
     * the security <code>Realm</code> of the incoming request.
     * <p>
     * This property should be set to <code>true</code> if the overall server
     * configuration requires that the <code>Realm</code> reauthenticate each
     * request thread.  An example of such a configuration would be one where
     * the <code>Realm</code> implementation provides security for both a
     * web tier and an associated EJB tier, and needs to set security
     * credentials on each request thread in order to support EJB access.
     * <p>
     * If this property is set to <code>true</code>, this Valve will set flags
     * on the request notifying the downstream Authenticator that the request
     * is associated with an SSO session.  The Authenticator will then call its
     * {@link AuthenticatorBase#reauthenticateFromSSO reauthenticateFromSSO}
     * method to attempt to reauthenticate the request to the
     * <code>Realm</code>, using any credentials that were cached with this
     * Valve.
     * <p>
     * The default value of this property is <code>false</code>, in order
     * to maintain backward compatibility with previous versions of Tomcat.
     *
     * @param required  <code>true</code> if it is required that a downstream
     *                  Authenticator reauthenticate each request before calls
     *                  to  <code>HttpServletRequest.setUserPrincipal()</code>
     *                  and <code>HttpServletRequest.setAuthType()</code> are
     *                  made; <code>false</code> if the <code>Valve</code> can
     *                  itself make those calls relying on the presence of a
     *                  valid SingleSignOn entry associated with the request.
     *
     * @see AuthenticatorBase#reauthenticateFromSSO
     */
    public void setRequireReauthentication(boolean required)
    {
        this.requireReauthentication = required;
    }


    // ------------------------------------------------ SessionListener Methods


    /**
     * Acknowledge the occurrence of the specified event.
     *
     * @param event SessionEvent that has occurred
     */
    @Override
    public void sessionEvent(SessionEvent event) {

        if (!getState().isAvailable()) {
            return;
        }

        // We only care about session destroyed events
        if (!Session.SESSION_DESTROYED_EVENT.equals(event.getType())
                && (!Session.SESSION_PASSIVATED_EVENT.equals(event.getType()))) {
            return;
        }

        // Look up the single session id associated with this session (if any)
        Session session = event.getSession();
        if (containerLog.isDebugEnabled()) {
            containerLog.debug("Process session destroyed on " + session);
        }

        String ssoId = null;
        synchronized (reverse) {
            ssoId = reverse.get(session);
        }
        if (ssoId == null) {
            return;
        }

        // Was the session destroyed as the result of a timeout?
        // If so, we'll just remove the expired session from the
        // SSO.  If the session was logged out, we'll log out
        // of all session associated with the SSO.
        if (((session.getMaxInactiveInterval() > 0)
            && (System.currentTimeMillis() - session.getThisAccessedTimeInternal() >=
                session.getMaxInactiveInterval() * 1000))
            || (Session.SESSION_PASSIVATED_EVENT.equals(event.getType()))
            || (!session.getManager().getContext().getState().isAvailable())) {
            removeSession(ssoId, session);
        } else {
            // The session was logged out.
            // Deregister this single session id, invalidating
            // associated sessions
            deregister(ssoId);
        }

    }


    // ---------------------------------------------------------- Valve Methods

    /**
     * Perform single-sign-on support processing for this request.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet error occurs
     */
    @Override
    public void invoke(Request request, Response response)
        throws IOException, ServletException {

        request.removeNote(Constants.REQ_SSOID_NOTE);

        // Has a valid user already been authenticated?
        if (containerLog.isDebugEnabled()) {
            containerLog.debug("Process request for '" + request.getRequestURI() + "'");
        }
        if (request.getUserPrincipal() != null) {
            if (containerLog.isDebugEnabled()) {
                containerLog.debug(" Principal '" + request.getUserPrincipal().getName() +
                    "' has already been authenticated");
            }
            getNext().invoke(request, response);
            return;
        }

        // Check for the single sign on cookie
        if (containerLog.isDebugEnabled()) {
            containerLog.debug(" Checking for SSO cookie");
        }
        Cookie cookie = null;
        Cookie cookies[] = request.getCookies();
        if (cookies == null) {
            cookies = new Cookie[0];
        }
        for (int i = 0; i < cookies.length; i++) {
            if (Constants.SINGLE_SIGN_ON_COOKIE.equals(cookies[i].getName())) {
                cookie = cookies[i];
                break;
            }
        }
        if (cookie == null) {
            if (containerLog.isDebugEnabled()) {
                containerLog.debug(" SSO cookie is not present");
            }
            getNext().invoke(request, response);
            return;
        }

        // Look up the cached Principal associated with this cookie value
        if (containerLog.isDebugEnabled()) {
            containerLog.debug(" Checking for cached principal for " + cookie.getValue());
        }
        SingleSignOnEntry entry = lookup(cookie.getValue());
        if (entry != null) {
            if (containerLog.isDebugEnabled()) {
                containerLog.debug(" Found cached principal '" +
                    (entry.getPrincipal() != null ? entry.getPrincipal().getName() : "") + "' with auth type '" +
                    entry.getAuthType() + "'");
            }
            request.setNote(Constants.REQ_SSOID_NOTE, cookie.getValue());
            // Only set security elements if reauthentication is not required
            if (!getRequireReauthentication()) {
                request.setAuthType(entry.getAuthType());
                request.setUserPrincipal(entry.getPrincipal());
            }
        } else {
            if (containerLog.isDebugEnabled()) {
                containerLog.debug(" No cached principal found, erasing SSO cookie");
            }
            cookie.setMaxAge(0);
            response.addCookie(cookie);
        }

        // Invoke the next Valve in our pipeline
        getNext().invoke(request, response);

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Associate the specified single sign on identifier with the
     * specified Session.
     *
     * @param ssoId Single sign on identifier
     * @param session Session to be associated
     */
    protected void associate(String ssoId, Session session) {

        if (containerLog.isDebugEnabled()) {
            containerLog.debug("Associate sso id " + ssoId + " with session " + session);
        }

        SingleSignOnEntry sso = lookup(ssoId);
        if (sso != null) {
            sso.addSession(this, session);
        }
        synchronized (reverse) {
            reverse.put(session, ssoId);
        }

    }

    /**
     * Deregister the specified session.  If it is the last session,
     * then also get rid of the single sign on identifier
     *
     * @param ssoId Single sign on identifier
     * @param session Session to be deregistered
     */
    protected void deregister(String ssoId, Session session) {

        synchronized (reverse) {
            reverse.remove(session);
        }

        SingleSignOnEntry sso = lookup(ssoId);
        if (sso == null) {
            return;
        }

        sso.removeSession(session);

        // see if we are the last session, if so blow away ssoId
        Session sessions[] = sso.findSessions();
        if (sessions == null || sessions.length == 0) {
            synchronized (cache) {
                cache.remove(ssoId);
            }
        }

    }


    /**
     * Deregister the specified single sign on identifier, and invalidate
     * any associated sessions.
     *
     * @param ssoId Single sign on identifier to deregister
     */
    protected void deregister(String ssoId) {

        if (containerLog.isDebugEnabled()) {
            containerLog.debug("Deregistering sso id '" + ssoId + "'");
        }

        // Look up and remove the corresponding SingleSignOnEntry
        SingleSignOnEntry sso = null;
        synchronized (cache) {
            sso = cache.remove(ssoId);
        }

        if (sso == null) {
            return;
        }

        // Expire any associated sessions
        Session sessions[] = sso.findSessions();
        for (int i = 0; i < sessions.length; i++) {
            if (containerLog.isTraceEnabled()) {
                containerLog.trace(" Invalidating session " + sessions[i]);
            }
            // Remove from reverse cache first to avoid recursion
            synchronized (reverse) {
                reverse.remove(sessions[i]);
            }
            // Invalidate this session
            sessions[i].expire();
        }

        // NOTE:  Clients may still possess the old single sign on cookie,
        // but it will be removed on the next request since it is no longer
        // in the cache

    }

    /**
     * Attempts reauthentication to the given <code>Realm</code> using
     * the credentials associated with the single sign-on session
     * identified by argument <code>ssoId</code>.
     * <p>
     * If reauthentication is successful, the <code>Principal</code> and
     * authorization type associated with the SSO session will be bound
     * to the given <code>Request</code> object via calls to
     * {@link Request#setAuthType Request.setAuthType()} and
     * {@link Request#setUserPrincipal Request.setUserPrincipal()}
     * </p>
     *
     * @param ssoId     identifier of SingleSignOn session with which the
     *                  caller is associated
     * @param realm     Realm implementation against which the caller is to
     *                  be authenticated
     * @param request   the request that needs to be authenticated
     *
     * @return  <code>true</code> if reauthentication was successful,
     *          <code>false</code> otherwise.
     */
    protected boolean reauthenticate(String ssoId, Realm realm,
                                     Request request) {

        if (ssoId == null || realm == null) {
            return false;
        }

        boolean reauthenticated = false;

        SingleSignOnEntry entry = lookup(ssoId);
        if (entry != null && entry.getCanReauthenticate()) {

            String username = entry.getUsername();
            if (username != null) {
                Principal reauthPrincipal =
                        realm.authenticate(username, entry.getPassword());
                if (reauthPrincipal != null) {
                    reauthenticated = true;
                    // Bind the authorization credentials to the request
                    request.setAuthType(entry.getAuthType());
                    request.setUserPrincipal(reauthPrincipal);
                }
            }
        }

        return reauthenticated;
    }


    /**
     * Register the specified Principal as being associated with the specified
     * value for the single sign on identifier.
     *
     * @param ssoId Single sign on identifier to register
     * @param principal Associated user principal that is identified
     * @param authType Authentication type used to authenticate this
     *  user principal
     * @param username Username used to authenticate this user
     * @param password Password used to authenticate this user
     */
    protected void register(String ssoId, Principal principal, String authType,
                  String username, String password) {

        if (containerLog.isDebugEnabled()) {
            containerLog.debug("Registering sso id '" + ssoId + "' for user '" +
                (principal != null ? principal.getName() : "") + "' with auth type '" + authType + "'");
        }

        synchronized (cache) {
            cache.put(ssoId, new SingleSignOnEntry(principal, authType,
                                                   username, password));
        }

    }


    /**
     * Updates any <code>SingleSignOnEntry</code> found under key
     * <code>ssoId</code> with the given authentication data.
     * <p>
     * The purpose of this method is to allow an SSO entry that was
     * established without a username/password combination (i.e. established
     * following DIGEST or CLIENT_CERT authentication) to be updated with
     * a username and password if one becomes available through a subsequent
     * BASIC or FORM authentication.  The SSO entry will then be usable for
     * reauthentication.
     * <p>
     * <b>NOTE:</b> Only updates the SSO entry if a call to
     * <code>SingleSignOnEntry.getCanReauthenticate()</code> returns
     * <code>false</code>; otherwise, it is assumed that the SSO entry already
     * has sufficient information to allow reauthentication and that no update
     * is needed.
     *
     * @param ssoId     identifier of Single sign to be updated
     * @param principal the <code>Principal</code> returned by the latest
     *                  call to <code>Realm.authenticate</code>.
     * @param authType  the type of authenticator used (BASIC, CLIENT_CERT,
     *                  DIGEST or FORM)
     * @param username  the username (if any) used for the authentication
     * @param password  the password (if any) used for the authentication
     */
    protected void update(String ssoId, Principal principal, String authType,
                          String username, String password) {

        SingleSignOnEntry sso = lookup(ssoId);
        if (sso != null && !sso.getCanReauthenticate()) {
            if (containerLog.isDebugEnabled()) {
                containerLog.debug("Update sso id " + ssoId + " to auth type " + authType);
            }

            synchronized(sso) {
                sso.updateCredentials(principal, authType, username, password);
            }

        }
    }


    /**
     * Look up and return the cached SingleSignOn entry associated with this
     * sso id value, if there is one; otherwise return <code>null</code>.
     *
     * @param ssoId Single sign on identifier to look up
     */
    protected SingleSignOnEntry lookup(String ssoId) {

        synchronized (cache) {
            return cache.get(ssoId);
        }

    }


    /**
     * Remove a single Session from a SingleSignOn.  Called when
     * a session is timed out and no longer active.
     *
     * @param ssoId Single sign on identifier from which to remove the session.
     * @param session the session to be removed.
     */
    protected void removeSession(String ssoId, Session session) {

        if (containerLog.isDebugEnabled()) {
            containerLog.debug("Removing session " + session.toString() + " from sso id " +
                ssoId );
        }

        // Get a reference to the SingleSignOn
        SingleSignOnEntry entry = lookup(ssoId);
        if (entry == null) {
            return;
        }

        // Remove the inactive session from SingleSignOnEntry
        entry.removeSession(session);

        // Remove the inactive session from the 'reverse' Map.
        synchronized(reverse) {
            reverse.remove(session);
        }

        // If there are not sessions left in the SingleSignOnEntry,
        // deregister the entry.
        if (entry.findSessions().length == 0) {
            deregister(ssoId);
        }
    }

}
