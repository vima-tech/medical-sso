package com.medical.union.gateway;

import com.medical.union.sso.MedicalUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把请求原样转给业务系统，并把已登录人员的身份放进请求头。
 *
 * <p>业务系统读的是 {@code X-Medical-Person-Id} 这类普通请求头，
 * 不需要懂 OIDC、不需要引依赖，改一行取用户的代码就能接上。
 */
public class UpstreamProxy {

    /** 注入给业务系统的身份请求头。 */
    static final String PERSON_ID = "X-Medical-Person-Id";
    static final String EMPLOYEE_NO = "X-Medical-Employee-No";
    static final String USERNAME = "X-Medical-Username";
    static final String NAME = "X-Medical-Name";
    static final String ORG_CODE = "X-Medical-Org-Code";
    static final String DEPT_CODE = "X-Medical-Dept-Code";
    static final String ROLES = "X-Medical-Roles";
    static final String GATEWAY_TOKEN = "X-Medical-Gateway-Token";

    /** 身份头的统一前缀。浏览器送来的同名头一律丢弃，见 {@link #copyRequestHeaders}。 */
    private static final String MEDICAL_PREFIX = "x-medical-";

    /** 逐跳头由每一段连接自己决定，不能照抄给下一段。 */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            // expect 由底层 HTTP 客户端自己管，转发时带上会被直接拒绝
            "expect");

    private final GatewayProperties properties;
    private final RestClient http;

    public UpstreamProxy(GatewayProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** @param user 已登录人员；公开路径上为 null，此时不注入任何身份头 */
    public void forward(HttpServletRequest request, HttpServletResponse response, MedicalUser user)
            throws IOException {
        URI target = URI.create(properties.resolvedUpstream() + rawPathWithQuery(request));
        byte[] body = request.getInputStream().readAllBytes();

        ResponseEntity<byte[]> upstream = http
                .method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(headers -> {
                    copyRequestHeaders(request, headers);
                    forwardedHeaders(request, headers);
                    if (user != null) {
                        injectIdentity(headers, user);
                    }
                    if (properties.getUpstreamToken() != null && !properties.getUpstreamToken().isBlank()) {
                        headers.set(GATEWAY_TOKEN, properties.getUpstreamToken());
                    }
                })
                .body(body)
                .retrieve()
                // 4xx/5xx 是业务系统的正常响应，原样带回浏览器，不要在网关这里变成异常
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(byte[].class);

        response.setStatus(upstream.getStatusCode().value());
        copyResponseHeaders(upstream.getHeaders(), response);
        byte[] payload = upstream.getBody();
        if (payload != null) {
            response.getOutputStream().write(payload);
        }
    }

    /**
     * 复制浏览器送来的请求头，但把所有 {@code X-Medical-*} 丢掉。
     *
     * <p>这是身份注入方案的关键一步：不丢的话，任何人加一个
     * {@code X-Medical-Person-Id} 头就能冒充别人，而且能冒充院长。
     */
    private void copyRequestHeaders(HttpServletRequest request, HttpHeaders headers) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || lower.startsWith(MEDICAL_PREFIX)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                headers.add(name, values.nextElement());
            }
        }
    }

    private void forwardedHeaders(HttpServletRequest request, HttpHeaders headers) {
        headers.set("X-Forwarded-For", request.getRemoteAddr());
        headers.set("X-Forwarded-Proto", request.getScheme());
        headers.set("X-Forwarded-Host", request.getServerName());
    }

    private void injectIdentity(HttpHeaders headers, MedicalUser user) {
        set(headers, PERSON_ID, user.personId());
        set(headers, EMPLOYEE_NO, user.employeeNo());
        set(headers, USERNAME, user.username());
        // 姓名是中文，请求头只能放 ASCII，用 URL 编码送出；业务系统解码一次即可
        set(headers, NAME, encode(user.name()));
        set(headers, ORG_CODE, user.organizationCode());
        set(headers, DEPT_CODE, user.departmentCode());
        if (user.clientRoles() != null && !user.clientRoles().isEmpty()) {
            headers.set(ROLES, String.join(",", user.clientRoles()));
        }
    }

    private static void set(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isEmpty()) {
            headers.set(name, value);
        }
    }

    private static String encode(String value) {
        return value == null ? null
                : java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void copyResponseHeaders(HttpHeaders upstream, HttpServletResponse response) {
        for (Map.Entry<String, List<String>> entry : upstream.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            // 长度和分块方式由本次连接重新决定，照抄会和实际内容对不上
            if (HOP_BY_HOP.contains(lower)) {
                continue;
            }
            for (String value : entry.getValue()) {
                response.addHeader(entry.getKey(), value);
            }
        }
    }

    /** 保留原始编码的路径与查询串，避免中文文件名等在转发时被二次编码。 */
    private static String rawPathWithQuery(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query == null ? uri : uri + "?" + query;
    }
}
