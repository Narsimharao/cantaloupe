package edu.illinois.library.cantaloupe.resource;

import edu.illinois.library.cantaloupe.delegate.JavaContext;
import edu.illinois.library.cantaloupe.image.Dimension;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.image.Metadata;
import edu.illinois.library.cantaloupe.image.ScaleConstraint;
import edu.illinois.library.cantaloupe.operation.Encode;
import edu.illinois.library.cantaloupe.operation.OperationList;
import edu.illinois.library.cantaloupe.test.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import static edu.illinois.library.cantaloupe.resource.RequestContextMap.CLIENT_IP_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.COOKIES_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.FULL_SIZE_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.IDENTIFIER_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.LOCAL_URI_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.METADATA_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.OPERATIONS_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.OUTPUT_FORMAT_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.REQUEST_HEADERS_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.REQUEST_URI_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.RESULTING_SIZE_KEY;
import static edu.illinois.library.cantaloupe.resource.RequestContextMap.SCALE_CONSTRAINT_KEY;

public class RequestContextTest extends BaseTest {

    private RequestContext instance;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();

        instance = new RequestContext();
        // client IP
        instance.setClientIP("1.2.3.4");
        // cookies
        Map<String,String> cookies = Map.of("cookie", "yes");
        instance.setCookies(cookies);
        // local URI
        instance.setLocalURI(new URI("http://example.org/cats"));
        // metadata
        instance.setMetadata(new Metadata());
        // operation list
        Identifier identifier = new Identifier("cats");
        Dimension fullSize    = new Dimension(200, 200);
        OperationList opList  = new OperationList(
                identifier, new Encode(Format.get("gif")));
        instance.setOperationList(opList, fullSize);
        // request headers
        Map<String,String> headers = Map.of("X-Cats", "Yes");
        instance.setRequestHeaders(headers);
        // request URI
        instance.setRequestURI(new URI("http://example.org/cats"));
        // scale constraint
        instance.setScaleConstraint(new ScaleConstraint(1, 2));
    }

    @Test
    void testSetClientIP() {
        instance.setClientIP("3.4.5.6");
        assertEquals("3.4.5.6", instance.getClientIP());
        instance.setClientIP(null);
        assertNull(instance.getClientIP());
    }

    @Test
    void testSetCookies() {
        instance.setCookies(Collections.emptyMap());
        assertNotNull(instance.getCookies());
        instance.setCookies(null);
        assertNull(instance.getCookies());
    }

    @Test
    void testSetIdentifier() {
        instance.setIdentifier(new Identifier("cats"));
        assertNotNull(instance.getIdentifier());
        instance.setIdentifier(null);
        assertNull(instance.getIdentifier());
    }

    @Test
    void testSetLocalURI() throws Exception {
        instance.setLocalURI(new URI("http://example.org/"));
        assertNotNull(instance.getLocalURI());
        instance.setLocalURI(null);
        assertNull(instance.getLocalURI());
    }

    @Test
    void testSetMetadata() {
        instance.setMetadata(new Metadata());
        assertNotNull(instance.getMetadata());
        instance.setMetadata(null);
        assertNull(instance.getMetadata());
    }

    @Test
    void testSetOperationList() {
        OperationList opList = new OperationList(
                new Identifier("cats"), new Encode(Format.get("jpg")));
        instance.setOperationList(opList, new Dimension(5, 5));
        assertNotNull(instance.getFullSize());
        assertNotNull(instance.getIdentifier());
        assertNotNull(instance.getOperationList());
        assertNotNull(instance.getOutputFormat());
        assertNotNull(instance.getResultingSize());

        instance.setOperationList(null, null);
        assertNull(instance.getFullSize());
        assertNull(instance.getIdentifier());
        assertNull(instance.getOperationList());
        assertNull(instance.getOutputFormat());
        assertNull(instance.getResultingSize());
    }

    @Test
    void testSetRequestHeaders() {
        instance.setRequestHeaders(Collections.emptyMap());
        assertNotNull(instance.getRequestHeaders());
        instance.setCookies(null);
        assertTrue(instance.getRequestHeaders().isEmpty());
    }

    @Test
    void testSetRequestURI() {
        instance.setRequestURI(URI.create("http://example.org/"));
        assertNotNull(instance.getRequestURI());
        instance.setRequestURI(null);
        assertNull(instance.getRequestURI());
    }

    @Test
    void testSetScaleConstraint() {
        instance.setScaleConstraint(new ScaleConstraint(1, 3));
        assertNotNull(instance.getScaleConstraint());
        instance.setScaleConstraint(null);
        assertNull(instance.getScaleConstraint());
    }

    @Test
    void testToJavaContext() {
        JavaContext actual = instance.toJavaContext();
        // client IP
        assertEquals("1.2.3.4", actual.getClientIPAddress());
        // cookies
        assertEquals("yes", actual.getCookies().get("cookie"));
        // full size
        assertNotNull(actual.getFullSize());
        // identifier
        assertEquals("cats", actual.getIdentifier());
        // operations
        assertNotNull(actual.getOperations());
        // output format
        assertEquals("image/gif", actual.getOutputFormat());
        // request headers
        assertEquals("Yes", ((Map<?, ?>) actual.getRequestHeaders()).get("X-Cats"));
        // request URI
        assertEquals("http://example.org/cats", actual.getRequestURI());
        // resulting size
        assertNotNull(actual.getResultingSize());
        // scale constraint
        assertNotNull(actual.getScaleConstraint());
    }

    @Test
    void testToJavaContextLiveView() {
        instance.setClientIP("2.3.4.5");
        JavaContext actual = instance.toJavaContext();
        assertEquals("2.3.4.5", actual.getClientIPAddress());
        instance.setClientIP("3.4.5.6");
        assertEquals("3.4.5.6", actual.getClientIPAddress());
    }

    @Test
    void testToMap() {
        Map<String,Object> actual = instance.toMap();
        // client IP
        assertEquals("1.2.3.4", actual.get(CLIENT_IP_KEY));
        // cookies
        assertEquals("yes", ((Map<?, ?>) actual.get(COOKIES_KEY)).get("cookie"));
        // full size
        assertNotNull(actual.get(FULL_SIZE_KEY));
        // identifier
        assertEquals("cats", actual.get(IDENTIFIER_KEY));
        // local URI
        assertEquals("http://example.org/cats", actual.get(LOCAL_URI_KEY));
        // metadata
        assertNotNull(actual.get(METADATA_KEY));
        // operations
        assertNotNull(actual.get(OPERATIONS_KEY));
        // output format
        assertEquals("image/gif", actual.get(OUTPUT_FORMAT_KEY));
        // request headers
        assertEquals("Yes", ((Map<?, ?>) actual.get(REQUEST_HEADERS_KEY)).get("X-Cats"));
        // request URI
        assertEquals("http://example.org/cats", actual.get(REQUEST_URI_KEY));
        // resulting size
        assertNotNull(actual.get(RESULTING_SIZE_KEY));
        // scale constraint
        assertNotNull(actual, SCALE_CONSTRAINT_KEY);
    }

    @Test
    void testToMapLiveView() {
        instance.setClientIP("2.3.4.5");
        Map<String,Object> actual = instance.toMap();
        assertEquals("2.3.4.5", actual.get(CLIENT_IP_KEY));
        instance.setClientIP("3.4.5.6");
        assertEquals("3.4.5.6", actual.get(CLIENT_IP_KEY));
    }

}
