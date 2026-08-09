package org.ruoyi.fault.controller.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 内部故障工具的服务间认证门禁。
 * <p>
 * 只信任 Servlet 容器提供的 TCP 来源地址，不读取可由调用方伪造的转发头。
 */
@Component
public class FaultToolInternalAuthFilter implements Filter {

    public static final String SERVICE_HEADER = "X-Internal-Service";
    public static final String TOKEN_HEADER = "X-Internal-Token";
    private static final String SERVICE_NAME = "hermes-fault";
    private static final String PATH_PREFIX = "/internal/fault-tools/";

    private final String token;

    public FaultToolInternalAuthFilter(@Value("${fault.internal-tools.token:}") String token) {
        this.token = token;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws ServletException, IOException {
        if (!(servletRequest instanceof HttpServletRequest request)
            || !(servletResponse instanceof HttpServletResponse response)
            || !request.getRequestURI().startsWith(request.getContextPath() + PATH_PREFIX)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String remoteAddress = request.getRemoteAddr();
        if (!isLoopbackOrPrivateAddress(remoteAddress)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "内部接口仅允许本机或私网访问");
            return;
        }
        if (!StringUtils.hasText(token)) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "内部接口 Token 未配置");
            return;
        }
        if (!SERVICE_NAME.equals(request.getHeader(SERVICE_HEADER))
            || !token.equals(request.getHeader(TOKEN_HEADER))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "内部服务认证失败");
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean isLoopbackOrPrivateAddress(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
