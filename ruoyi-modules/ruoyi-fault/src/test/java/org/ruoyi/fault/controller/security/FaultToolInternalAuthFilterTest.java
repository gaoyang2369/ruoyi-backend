package org.ruoyi.fault.controller.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class FaultToolInternalAuthFilterTest {

    private static final String TEST_TOKEN = "test-secret";

    @Test
    void allowsPrivateSourceWithMatchingServiceCredentials() throws Exception {
        MockHttpServletRequest request = request("172.18.0.5");
        request.addHeader(FaultToolInternalAuthFilter.SERVICE_HEADER, "hermes-fault");
        request.addHeader(FaultToolInternalAuthFilter.TOKEN_HEADER, TEST_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(TEST_TOKEN).doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsPublicSourceEvenWhenForwardedHeaderClaimsLoopback() throws Exception {
        MockHttpServletRequest request = request("8.8.8.8");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader(FaultToolInternalAuthFilter.SERVICE_HEADER, "hermes-fault");
        request.addHeader(FaultToolInternalAuthFilter.TOKEN_HEADER, TEST_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(TEST_TOKEN).doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
        assertEquals("内部接口仅允许本机或私网访问", response.getErrorMessage());
    }

    @Test
    void rejectsWrongTokenFromPrivateSource() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader(FaultToolInternalAuthFilter.SERVICE_HEADER, "hermes-fault");
        request.addHeader(FaultToolInternalAuthFilter.TOKEN_HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(TEST_TOKEN).doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
        assertEquals("内部服务认证失败", response.getErrorMessage());
    }

    @Test
    void closesEndpointWhenTokenIsNotConfigured() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader(FaultToolInternalAuthFilter.SERVICE_HEADER, "hermes-fault");
        request.addHeader(FaultToolInternalAuthFilter.TOKEN_HEADER, "any-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(null).doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertNull(chain.getRequest());
        assertEquals("内部接口 Token 未配置", response.getErrorMessage());
    }

    @Test
    void recognizesLoopbackAndPrivateAddresses() {
        assertTrue(FaultToolInternalAuthFilter.isLoopbackOrPrivateAddress("127.0.0.1"));
        assertTrue(FaultToolInternalAuthFilter.isLoopbackOrPrivateAddress("10.0.0.8"));
        assertTrue(FaultToolInternalAuthFilter.isLoopbackOrPrivateAddress("172.31.0.8"));
        assertTrue(FaultToolInternalAuthFilter.isLoopbackOrPrivateAddress("192.168.1.8"));
        assertTrue(FaultToolInternalAuthFilter.isLoopbackOrPrivateAddress("::1"));
    }

    private static FaultToolInternalAuthFilter filter(String token) {
        return new FaultToolInternalAuthFilter(token);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/fault-tools/status");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
