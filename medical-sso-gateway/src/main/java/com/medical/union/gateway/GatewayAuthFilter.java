package com.medical.union.gateway;

import com.medical.union.sso.MedicalSsoExchange;
import com.medical.union.sso.MedicalSsoProperties;
import com.medical.union.sso.MedicalSsoStateStore;
import com.medical.union.sso.MedicalUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 网关的全部逻辑：没登录就带去统一登录页，登录了就把请求转给业务系统。
 *
 * <p>业务系统一行代码都不用改。它甚至不知道有统一认证这回事，
 * 只会看到每个请求都带着 {@code X-Medical-*} 头。
 */
public class GatewayAuthFilter extends OncePerRequestFilter {

    /** 网关自用的路径。用双下划线前缀，避免和业务系统的路由撞车。 */
    static final String CALLBACK_PATH = "/__sso/callback";
    static final String LOGOUT_PATH = "/__sso/logout";

    private static final String COOKIE = "MEDICAL_GW_SESSION";
    private static final String STATE_PREFIX = "medical:gw:state:";

    private final GatewayProperties gateway;
    private final MedicalSsoProperties sso;
    private final MedicalSsoExchange exchange;
    private final MedicalSsoStateStore stateStore;
    private final GatewaySessionStore sessions;
    private final UpstreamProxy proxy;

    public GatewayAuthFilter(GatewayProperties gateway, MedicalSsoProperties sso,
                             MedicalSsoExchange exchange, MedicalSsoStateStore stateStore,
                             GatewaySessionStore sessions, UpstreamProxy proxy) {
        this.gateway = gateway;
        this.sso = sso;
        this.exchange = exchange;
        this.stateStore = stateStore;
        this.sessions = sessions;
        this.proxy = proxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (CALLBACK_PATH.equals(path)) {
            handleCallback(request, response);
            return;
        }
        if (LOGOUT_PATH.equals(path)) {
            handleLogout(request, response);
            return;
        }
        // actuator 留给运维探活，不代理也不要求登录
        if (path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        Optional<MedicalUser> user = readCookie(request).flatMap(sessions::find);
        if (user.isPresent()) {
            proxy.forward(request, response, user.get());
            return;
        }
        if (gateway.isPublic(path)) {
            // 公开路径不注入身份：业务系统看到没有身份头，就知道这是匿名访问
            proxy.forward(request, response, null);
            return;
        }
        startLogin(request, response);
    }

    private void startLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 非页面请求不要重定向：浏览器的 fetch 收到 302 会跟到登录页，
        // 前端拿到一段 HTML 却当成 JSON 解析，报出与登录毫不相干的错。
        if (!isPageRequest(request)) {
            response.setStatus(401);
            response.setHeader("X-Medical-Login-Url", loginUrl(request));
            return;
        }
        response.sendRedirect(loginUrl(request));
    }

    private String loginUrl(HttpServletRequest request) {
        String state = MedicalSsoExchange.randomUrlSafe();
        MedicalSsoExchange.Attempt attempt = exchange.newAttempt(state);
        // 记住用户本来要去的地址，登录完把他送回原处而不是首页
        stateStore.save(STATE_PREFIX + state,
                attempt.codeVerifier() + "&" + urlEncode(originalTarget(request)),
                sso.getBridge().getStateTtl());
        return attempt.authorizationUrl();
    }

    private void handleCallback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String error = request.getParameter("error");
        if (error != null) {
            deny(response, "统一认证拒绝了本次登录：" + error);
            return;
        }
        String code = request.getParameter("code");
        String state = request.getParameter("state");
        if (code == null || state == null) {
            deny(response, "回调参数不完整");
            return;
        }
        String stored = stateStore.take(STATE_PREFIX + state);
        if (stored == null) {
            deny(response, "登录已过期，请重新访问本系统");
            return;
        }
        String[] parts = stored.split("&", -1);
        String codeVerifier = parts[0];
        String target = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "/";

        MedicalUser user;
        try {
            user = exchange.exchange(code, codeVerifier);
        } catch (RuntimeException ex) {
            deny(response, "换取身份失败，请重试");
            return;
        }

        String id = sessions.create(user, gateway.getSessionTtl());
        Cookie cookie = new Cookie(COOKIE, id);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
        response.sendRedirect(safe(target));
    }

    private void handleLogout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        readCookie(request).ifPresent(sessions::remove);
        Cookie cookie = new Cookie(COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        // 只退本系统。要连统一认证一起退，得跳统一认证的登出地址，
        // 那会把该用户在所有业务系统的登录状态一起清掉，不是这里该替他决定的事。
        response.sendRedirect("/");
    }

    private static void deny(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(403);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<!doctype html><meta charset=\"utf-8\">"
                + "<title>登录未完成</title>"
                + "<div style=\"font:15px/1.8 system-ui;max-width:32rem;margin:20vh auto;color:#2b2b2b\">"
                + "<h1 style=\"font-size:1.15rem\">登录未完成</h1><p>" + escape(reason)
                + "</p><p><a href=\"/\">返回本系统</a></p></div>");
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** 页面请求才做跳转。判断依据是浏览器地址栏导航会声明接受 HTML。 */
    private static boolean isPageRequest(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String accept = request.getHeader("Accept");
        return accept == null || accept.contains("text/html") || accept.contains("*/*");
    }

    private static String originalTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query == null ? uri : uri + "?" + query;
    }

    /** 只回站内地址，挡掉被人构造出来把用户带去外部站点的跳转。 */
    private static String safe(String target) {
        return target.startsWith("/") && !target.startsWith("//") ? target : "/";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
