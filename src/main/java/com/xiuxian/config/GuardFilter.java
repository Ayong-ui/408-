package com.xiuxian.config;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 洞府禁制。应用本身不做登录，安全靠三层：
 * <ol>
 *   <li>只监听 127.0.0.1（见 application.yml），手机经 Tailscale 进来；</li>
 *   <li>只认 localhost / 127.0.0.1 / *.ts.net 和配置里的主机名，挡住 DNS 重绑定；</li>
 *   <li>写操作必须带 X-Xiuxian 头，并拒绝跨站请求，挡住恶意网页借浏览器偷偷调用接口。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GuardFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> extraHosts;

    public GuardFilter(XiuxianProperties props) {
        this.extraHosts = props.allowedHosts().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!hostAllowed(req.getServerName())) {
            deny(res, "此处乃禁地：未识别的来访地址");
            return;
        }
        if (req.getRequestURI().startsWith("/api/")) {
            if ("cross-site".equalsIgnoreCase(req.getHeader("Sec-Fetch-Site"))) {
                deny(res, "此处乃禁地：拒绝跨站传音");
                return;
            }
            String origin = req.getHeader("Origin");
            if (origin != null && !originAllowed(origin)) {
                deny(res, "此处乃禁地：拒绝外来传音");
                return;
            }
            if (!SAFE_METHODS.contains(req.getMethod()) && !"1".equals(req.getHeader("X-Xiuxian"))) {
                deny(res, "此处乃禁地：缺少洞府令牌");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private boolean hostAllowed(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
                || h.endsWith(".ts.net") || extraHosts.contains(h);
    }

    private boolean originAllowed(String origin) {
        try {
            return hostAllowed(URI.create(origin).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void deny(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
