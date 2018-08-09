package edu.illinois.library.cantaloupe.resource;

import edu.illinois.library.cantaloupe.Application;
import edu.illinois.library.cantaloupe.auth.AuthInfo;
import edu.illinois.library.cantaloupe.auth.Authorizer;
import edu.illinois.library.cantaloupe.auth.AuthorizerFactory;
import edu.illinois.library.cantaloupe.auth.CredentialStore;
import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.http.Method;
import edu.illinois.library.cantaloupe.http.Reference;
import edu.illinois.library.cantaloupe.http.Status;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.script.DelegateProxy;
import edu.illinois.library.cantaloupe.script.DelegateProxyService;
import edu.illinois.library.cantaloupe.script.DisabledException;
import edu.illinois.library.cantaloupe.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <p>Abstract HTTP resource. Instances should subclass and override one or
 * more of the HTTP-method-specific methods {@link #doGET()} etc., and may
 * optionally use {@link #doInit()} and {@link #destroy()}.</p>
 *
 * <p>This class is loosely modeled on Restlet framework's {@literal
 * ServerResource}, both because this application used to use Restlet, and
 * because it's a good design.</p>
 *
 * <p>Unlike {@link javax.servlet.http.HttpServlet}s, instances will only be
 * used once and will not be shared across threads.</p>
 */
public abstract class AbstractResource {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AbstractResource.class);

    /**
     * Replaces {@link #PUBLIC_IDENTIFIER_HEADER_DEPRECATED}.
     */
    public static final String PUBLIC_IDENTIFIER_HEADER = "X-Forwarded-ID";

    /**
     * @deprecated Since version 4.0. Still respected, but superseded by
     *             {@link #PUBLIC_IDENTIFIER_HEADER}.
     */
    @Deprecated
    public static final String PUBLIC_IDENTIFIER_HEADER_DEPRECATED = "X-IIIF-ID";

    protected static final String RESPONSE_CONTENT_DISPOSITION_QUERY_ARG =
            "response-content-disposition";

    /**
     * Set by {@link #getDelegateProxy()}.
     */
    private DelegateProxy delegateProxy;

    private final RequestContext requestContext = new RequestContext();

    private List<String> pathArguments = Collections.emptyList();
    private Request request;
    private HttpServletResponse response;

    /**
     * <p>Initialization method, called after all necessary setters have been
     * called but before any request-handler method (like {@link #doGET()}
     * etc.)</p>
     *
     * <p>Overrides must call {@literal super}.</p>
     */
    public void doInit() throws Exception {
        response.setHeader("X-Powered-By",
                Application.getName() + "/" + Application.getVersion());

        if (DelegateProxyService.isEnabled()) {
            requestContext.setRequestURI(request.getReference().toURI());
            requestContext.setRequestHeaders(request.getHeaders().toMap());
            requestContext.setClientIP(getCanonicalClientIPAddress());
            requestContext.setCookies(request.getCookies());
            requestContext.setIdentifier(getIdentifier());
        }

        // Log request info.
        String headersStr = request.getHeaders().stream()
                .map(h -> h.getName() + ": " +
                        ("Authorization".equals(h.getName()) ? "******" : h.getValue()))
                .collect(Collectors.joining("; "));
        LOGGER.info("doInit(): handling {} {} [{}]",
                request.getMethod(),
                request.getReference().getPath(),
                headersStr);
    }

    /**
     * <p>Called at the end of the instance's lifecycle.</p>
     *
     * <p>Overrides must call {@literal super}.</p>
     */
    public void destroy() {
    }

    /**
     * <p>Must be overridden by implementations that support {@literal
     * DELETE}.</p>
     *
     * <p>Overrides must not call {@literal super}.</p>
     */
    public void doDELETE() throws Exception {
        response.setStatus(Status.METHOD_NOT_ALLOWED.getCode());
    }

    /**
     * <p>Must be overridden by implementations that support {@literal GET}.</p>
     *
     * <p>Overrides must not call {@literal super}.</p>
     */
    public void doGET() throws Exception {
        response.setStatus(Status.METHOD_NOT_ALLOWED.getCode());
    }

    /**
     * This implementation simply calls {@link #doGET}. When that is
     * overridden, this may also be overridden in order to set headers only and
     * not compute a response body.
     */
    public void doHEAD() throws Exception {
        doGET();
    }

    final void doOPTIONS() {
        Method[] methods = getSupportedMethods();
        if (methods.length > 0) {
            response.setStatus(Status.NO_CONTENT.getCode());
            response.setHeader("Allow", Arrays.stream(methods)
                    .map(Method::toString)
                    .collect(Collectors.joining(",")));
        } else {
            response.setStatus(Status.METHOD_NOT_ALLOWED.getCode());
        }
    }

    /**
     * <p>Must be overridden by implementations that support {@literal
     * POST}.</p>
     *
     * <p>Overrides must not call {@literal super}.</p>
     */
    public void doPOST() throws Exception {
        response.setStatus(Status.METHOD_NOT_ALLOWED.getCode());
    }

    /**
     * <p>Must be overridden by implementations that support {@literal PUT}.</p>
     *
     * <p>Overrides must not call {@literal super}.</p>
     */
    public void doPUT() throws Exception {
        response.setStatus(Status.METHOD_NOT_ALLOWED.getCode());
    }

    /**
     * Checks the {@literal Authorization} header for credentials that exist in
     * the given {@link CredentialStore}. If not found, sends a {@literal
     * WWW-Authenticate} header and throws an exception.
     *
     * @param realm           Basic realm.
     * @param credentialStore Credential store.
     * @throws ResourceException if authentication failed.
     */
    protected final void authenticateUsingBasic(String realm,
                                                CredentialStore credentialStore)
            throws ResourceException {
        boolean isAuthenticated = false;
        String header = getRequest().getHeaders().getFirstValue("Authorization", "");
        if ("Basic ".equals(header.substring(0, Math.min(header.length(), 6)))) {
            String encoded = header.substring(6);
            String decoded = new String(Base64.getDecoder().decode(encoded.getBytes()));
            String[] parts = decoded.split(":");
            if (parts.length == 2) {
                String user = parts[0];
                String secret = parts[1];
                if (secret.equals(credentialStore.getSecret(user))) {
                    isAuthenticated = true;
                }
            }
        }

        if (!isAuthenticated) {
            getResponse().setHeader("WWW-Authenticate",
                    "Basic realm=\"" + realm + "\" charset=\"UTF-8\"");
            throw new ResourceException(Status.UNAUTHORIZED);
        }
    }

    /**
     * Uses an {@link Authorizer} to determine how to respond to the request.
     * The response will be modified if necessary.
     *
     * @return Whether authorization was successful. {@literal false} indicates
     *         a redirect, and client code should abort.
     * @throws IOException if there was an I/O error while checking
     *                     authorization.
     * @throws ResourceException if authorization resulted in an HTTP 400-level
     *                           response.
     */
    protected final boolean authorize() throws IOException, ResourceException {
        final Authorizer authorizer =
                new AuthorizerFactory().newAuthorizer(getDelegateProxy());
        final AuthInfo info = authorizer.authorize();

        if (info != null) {
            final int code = info.getResponseStatus();
            final String location = info.getRedirectURI();

            if (location != null) {
                getResponse().setStatus(code);
                getResponse().setHeader("Cache-Control", "no-cache");
                getResponse().setHeader("Location", location);
                new StringRepresentation("Redirect: " + location)
                        .write(getResponse().getOutputStream());
                return false;
            } else if (code >= 400) {
                getResponse().setStatus(code);
                getResponse().setHeader("Cache-Control", "no-cache");
                if (code == 401) {
                    getResponse().setHeader("WWW-Authenticate",
                            info.getChallengeValue());
                }
                throw new ResourceException(new Status(code));
            }
        }
        return true;
    }

    /**
     * @return User agent's IP address, respecting the {@literal
     *         X-Forwarded-For} request header, if present.
     */
    private String getCanonicalClientIPAddress() {
        // The value is expected to be in the format: "client, proxy1, proxy2"
        final String forwardedFor =
                request.getHeaders().getFirstValue("X-Forwarded-For", "");
        if (!forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        } else {
            // Fall back to the client IP address.
            return request.getRemoteAddr();
        }
    }

    /**
     * @return Map of template variables common to most or all templates, such
     *         as variables that appear in a common header.
     */
    protected final Map<String, Object> getCommonTemplateVars() {
        final Map<String,Object> vars = new HashMap<>();
        vars.put("version", Application.getVersion());

        String baseURI = getPublicRootReference().toString();
        // A trailing slash is needed to make the <base> tag work.
        if (!baseURI.endsWith("/")) {
            baseURI += "/";
        }
        vars.put("baseUri", baseURI);
        return vars;
    }

    /**
     * @return Instance for the current request. The result is cached. May be
     *         {@literal null}.
     */
    protected final DelegateProxy getDelegateProxy() {
        if (delegateProxy == null && DelegateProxyService.isEnabled()) {
            DelegateProxyService service = DelegateProxyService.getInstance();
            try {
                delegateProxy = service.newDelegateProxy(getRequestContext());
            } catch (DisabledException e) {
                LOGGER.debug("newDelegateProxy(): {}", e.getMessage());
            }
        }
        return delegateProxy;
    }

    /**
     * Returns the decoded identifier path component of the URI. (This may not
     * be the identifier that the client supplies or sees; for that, use {@link
     * #getPublicIdentifier()}.)
     *
     * @return Identifier, or {@literal null} if the URI does not have an
     *         identifier path component.
     * @see #getPublicIdentifier()
     */
    protected Identifier getIdentifier() {
        String pathComponent = getIdentifierPathComponent();
        if (pathComponent != null) {
            return Identifier.fromURIPathComponent(pathComponent);
        }
        return null;
    }

    /**
     * <p>Returns the first {@link #getPathArguments() path argument}. (Most
     * resources have an identifier as the first path argument, so this will
     * work for them, but if not, an override will be necessary.)</p>
     *
     * <p>The result is not decoded.</p>
     *
     * @return Identifier, or {@literal null} if no path arguments are
     *         available.
     */
    private String getIdentifierPathComponent() {
        List<String> args = getPathArguments();
        return (!args.isEmpty()) ? args.get(0) : null;
    }

    /**
     * Returns the segments of the URI path that are considered arguments.
     * (These may correspond to regex match groups in {@link Route}.)
     *
     * @return Path arguments, or an empty list if there are none.
     */
    protected final List<String> getPathArguments() {
        return pathArguments;
    }

    /**
     * @return List of client-preferred media types as expressed in the
     *         {@literal Accept} request header.
     * @see    <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html">
     *         RFC 2616</a>
     */
    protected final List<String> getPreferredMediaTypes() {
        class Preference implements Comparable<Preference> {
            private String mediaType;
            private float qValue;

            @Override
            public int compareTo(Preference o) {
                if (o.qValue < qValue) {
                    return -1;
                } else if (o.qValue > o.qValue) {
                    return 1;
                }
                return 0;
            }
        }

        final List<Preference> preferences = new ArrayList<>();
        final String acceptHeader = request.getHeaders().getFirstValue("Accept");
        if (acceptHeader != null) {
            String[] clauses = acceptHeader.split(",");
            for (String clause : clauses) {
                String[] parts = clause.split(";");
                Preference preference = new Preference();
                preference.mediaType = parts[0].trim();
                if (parts.length > 1) {
                    String q = parts[1].trim();
                    if (q.startsWith("q=")) {
                        q = q.substring(2, q.length());
                        preference.qValue = Float.parseFloat(q);
                    }
                } else {
                    preference.qValue = 1;
                }
                preferences.add(preference);
            }
        }
        return preferences.stream()
                .sorted()
                .map(p -> p.mediaType)
                .collect(Collectors.toList());
    }

    /**
     * <p>Returns the identifier that the client sees. This will be the value
     * of either the {@link #PUBLIC_IDENTIFIER_HEADER} or {@link
     * #PUBLIC_IDENTIFIER_HEADER_DEPRECATED} headers, if available, or else
     * the {@literal identifier} URI path component.</p>
     *
     * <p>The result is not decoded, as the encoding may be influenced by
     * {@link Key#SLASH_SUBSTITUTE}, for example.</p>
     *
     * @see #getIdentifier()
     */
    protected String getPublicIdentifier() {
        String urlID = getIdentifierPathComponent();
        // Try to use the value of the identifier header, if supplied.
        String header = PUBLIC_IDENTIFIER_HEADER;
        String headerID = request.getHeaders().getFirstValue(header, "");
        if (headerID.isEmpty()) {
            // Fall back to the deprecated one.
            header = PUBLIC_IDENTIFIER_HEADER_DEPRECATED;
            headerID = request.getHeaders().getFirstValue(header, "");
        }
        return headerID.isEmpty() ? urlID : headerID;
    }

    /**
     * <p>Returns the current reference.</p>
     *
     * <p>{@link Key#BASE_URI} is respected, if set. Otherwise, the {@literal
     * X-Forwarded-*} request headers are respected, if available. Finally,
     * Servlet-supplied information is used otherwise.</p>
     *
     * <p>Note that this value may not be something the client is expecting to
     * see; for example, any {@link #getIdentifier() identifier} present in the
     * URI path is not {@link #getPublicIdentifier() translated}.</p>
     *
     * @see #getPublicRootReference()
     */
    protected Reference getPublicReference() {
        final Reference requestRef = new Reference(getRequest().getReference());
        final Reference appRootRef = new Reference(requestRef);
        appRootRef.setPath(getRequest().getContextPath());

        String appRootRelativePath =
                requestRef.getRelativePath(appRootRef.getPath());
        Reference newRef = new Reference(requestRef);

        // If base_uri is set in the configuration, build a URI based on that.
        final String baseUri = Configuration.getInstance()
                .getString(Key.BASE_URI, "");
        if (!baseUri.isEmpty()) {
            final Reference baseRef = new Reference(baseUri);
            newRef = new Reference(appRootRef);
            newRef.setScheme(baseRef.getScheme());
            newRef.setHost(baseRef.getHost());
            newRef.setPort(baseRef.getPort());

            String pathStr = StringUtils.stripEnd(baseRef.getPath(), "/");
            if (!appRootRelativePath.isEmpty()) {
                pathStr = StringUtils.stripEnd(pathStr, "/") + "/" +
                        StringUtils.stripStart(appRootRelativePath, "/");
            }
            newRef.setPath(pathStr);

            LOGGER.debug("Base URI from assembled from {} key: {}",
                    Key.BASE_URI, newRef);
        } else {
            // Try to use X-Forwarded-* headers.
            // N.B.: Header values may be comma-separated lists when operating
            // behind a chain of reverse proxies.

            // This may or may not include a port number. If it does, it should
            // be used if X-Forwarded-Port was not sent.
            final String hostHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Host", "");
            if (!hostHeader.isEmpty()) {
                String hostStr = hostHeader.split(",")[0].trim();
                String[] parts = hostStr.split(":");
                newRef.setHost(parts[0]);
                if (parts.length > 1) {
                    newRef.setPort(Integer.parseInt(parts[1]));
                }
            }

            String protoHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Proto", "");
            String protoStr = newRef.getScheme();
            if (!protoHeader.isEmpty()) {
                protoStr = protoHeader.split(",")[0].trim().toLowerCase();
                newRef.setScheme(protoStr);
            }

            final String portHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Port", "");
            if (!portHeader.isEmpty()) {
                String portStr = portHeader.split(",")[0].trim();
                Integer port = Integer.parseInt(portStr);
                if ((port == 80 && "http".equalsIgnoreCase(protoStr)) ||
                        (port == 443 && "https".equalsIgnoreCase(protoStr))) {
                    port = -1;
                }
                newRef.setPort(port);
            }

            final String pathHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Path", "");
            if (!pathHeader.isEmpty()) {
                String pathStr = pathHeader.split(",")[0].trim();
                if (!appRootRelativePath.isEmpty()) {
                    pathStr = StringUtils.stripEnd(pathStr, "/") + "/" +
                            StringUtils.stripStart(appRootRelativePath, "/");
                }
                newRef.setPath(StringUtils.stripEnd(pathStr, "/"));
            }

            LOGGER.debug("Base URI assembled from X-Forwarded headers: {}",
                    newRef);
        }
        return newRef;
    }

    /**
     * <p>Returns the root reference, i.e. a reference to the base URI path of
     * the application.</p>
     *
     * <p>{@link Key#BASE_URI} is respected, if set. Otherwise, the {@literal
     * X-Forwarded-*} request headers are respected, if available. Finally,
     * Servlet-supplied information is used otherwise.</p>
     *
     * @see #getPublicReference()
     */
    protected Reference getPublicRootReference() {
        final Reference ref = new Reference(getRequest().getReference());
        ref.setPath(getRequest().getContextPath());

        // If base_uri is set in the configuration, build a URI based on that.
        final String baseUri = Configuration.getInstance()
                .getString(Key.BASE_URI, "");
        if (!baseUri.isEmpty()) {
            final Reference baseRef = new Reference(baseUri);
            ref.setScheme(baseRef.getScheme());
            ref.setHost(baseRef.getHost());
            ref.setPort(baseRef.getPort());
            ref.setPath(StringUtils.stripEnd(baseRef.getPath(), "/"));
        } else {
            // Try to use X-Forwarded-* headers.
            // N.B.: Header values may be comma-separated lists when operating
            // behind a chain of reverse proxies.

            // This may or may not include a port number. If it does, it should
            // be used if X-Forwarded-Port was not sent.
            final String hostHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Host", "");
            if (!hostHeader.isEmpty()) {
                String hostStr = hostHeader.split(",")[0].trim();
                String[] parts = hostStr.split(":");
                ref.setHost(parts[0]);
                if (parts.length > 1) {
                    ref.setPort(Integer.parseInt(parts[1]));
                }
            }

            String protoHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Proto", "");
            String protoStr = ref.getScheme();
            if (!protoHeader.isEmpty()) {
                protoStr = protoHeader.split(",")[0].trim().toLowerCase();
                ref.setScheme(protoStr);
            }

            final String portHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Port", "");
            if (!portHeader.isEmpty()) {
                String portStr = portHeader.split(",")[0].trim();
                Integer port = Integer.parseInt(portStr);
                if ((port == 80 && "http".equalsIgnoreCase(protoStr)) ||
                        (port == 443 && "https".equalsIgnoreCase(protoStr))) {
                    port = -1;
                }
                ref.setPort(port);
            }

            final String pathHeader = getRequest().getHeaders()
                    .getFirstValue("X-Forwarded-Path", "");
            if (!pathHeader.isEmpty()) {
                String pathStr = pathHeader.split(",")[0].trim();
                ref.setPath(StringUtils.stripEnd(pathStr, "/"));
            }
        }
        return ref;
    }

    /**
     * <p>Returns a value for a {@literal Content-Disposition} header based on
     * the following in order of preference:</p>
     *
     * <ol>
     *     <li>The value of the {@link #RESPONSE_CONTENT_DISPOSITION_QUERY_ARG}
     *     query argument</li>
     *     <li>The setting of {@link Key#IIIF_CONTENT_DISPOSITION} in the
     *     application configuration</li>
     * </ol>
     *
     * <p>Falls back to an empty disposition, signified by a {@literal null}
     * return value.</p>
     *
     * <p>If the disposition is {@literal attachment} and the filename is not
     * set, it will be set to a reasonable value based on the given identifier
     * and output format.</p>
     *
     * @param queryArg Value of the {@link
     *                 #RESPONSE_CONTENT_DISPOSITION_QUERY_ARG} query argument.
     * @param identifier
     * @param outputFormat
     */
    protected String getRepresentationDisposition(String queryArg,
                                                  Identifier identifier,
                                                  Format outputFormat) {
        String disposition = null;
        // If a query argument value is available, use that. Otherwise, consult
        // the configuration.
        if (queryArg != null) {
            if (queryArg.startsWith("inline")) {
                disposition = "inline";
            } else if (queryArg.startsWith("attachment")) {
                Pattern pattern = Pattern.compile(".*filename=\"(.*)\".*");
                Matcher m = pattern.matcher(queryArg);
                String filename;
                if (m.matches()) {
                    // Filter out filename-unsafe characters as well as ".."
                    filename = StringUtils.sanitize(m.group(1),
                            Pattern.compile("\\.\\."),
                            Pattern.compile(StringUtils.FILENAME_REGEX));
                } else {
                    filename = getContentDispositionFilename(identifier,
                            outputFormat);
                }
                disposition = "attachment; filename=" + filename;
            }
        } else {
            switch (Configuration.getInstance()
                    .getString(Key.IIIF_CONTENT_DISPOSITION, "none")) {
                case "inline":
                    disposition = "inline; filename=" +
                            getContentDispositionFilename(identifier, outputFormat);
                    break;
                case "attachment":
                    disposition = "attachment; filename=" +
                            getContentDispositionFilename(identifier, outputFormat);
                    break;
            }
        }
        return disposition;
    }

    private String getContentDispositionFilename(Identifier identifier,
                                                 Format outputFormat) {
        return identifier.toString().replaceAll(StringUtils.FILENAME_REGEX, "_") +
                "." + outputFormat.getPreferredExtension();
    }

    /**
     * @return Request being handled.
     */
    protected final Request getRequest() {
        return request;
    }

    /**
     * @return Instance with basic info already set.
     */
    protected final RequestContext getRequestContext() {
        return requestContext;
    }

    /**
     * @return Response to be sent.
     */
    protected final HttpServletResponse getResponse() {
        return response;
    }

    /**
     * <p>This implementation returns a one-element array containing {@link
     * Method#OPTIONS}. It can be overridden to declare or not declare support
     * for:</p>
     *
     * <ul>
     *     <li>{@link #doGET() GET} (note that {@link #doHEAD() HEAD} is
     *     implicitly supported when this is supported)</li>
     *     <li>{@link #doPOST() POST}</li>
     *     <li>{@link #doPUT() PUT}</li>
     *     <li>{@link #doDELETE() DELETE}</li>
     * </ul>
     *
     * <p>Overrides should include {@link Method#OPTIONS}.</p>
     */
    public Method[] getSupportedMethods() {
        return new Method[] { Method.OPTIONS };
    }

    /**
     * @param limitToTypes Media types to limit the result to, in order of
     *                     most to least preferred by the application.
     * @return Best media type conforming to client preferences as expressed in
     *         the {@literal Accept} header.
     */
    protected final String negotiateContentType(List<String> limitToTypes) {
        return getPreferredMediaTypes().stream()
                .filter(limitToTypes::contains)
                .findFirst()
                .orElse(null);
    }

    final void setPathArguments(List<String> pathArguments) {
        this.pathArguments = pathArguments;
    }

    /**
     * @param request Request being handled.
     */
    public final void setRequest(Request request) {
        this.request = request;
    }

    /**
     * @param response Response that will be sent.
     */
    public final void setResponse(HttpServletResponse response) {
        this.response = response;
    }

}
