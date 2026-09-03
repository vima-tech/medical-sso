package com.medical.union.sso;

import javax.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Duration;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桥接模式的端点。子系统不需要自己写这些，接入时只实现 {@link MedicalIdentityBridge}。
 *
 * <pre>
 * GET  /api/auth/sso/start            发起登录，跳转统一登录页
 * GET  /api/auth/sso/callback         统一认证回调，换取身份并交给子系统发凭证
 * POST /api/auth/sso/exchange-ticket  前端用一次性票据换回凭证
 * POST /api/auth/sso/bind             首次绑定：用旧账号密码确认是同一个人
 * </pre>
 */
@RestController
@RequestMapping("/api/auth/sso")
public class MedicalSsoBridgeController {

    /** 暂存条目的前缀，避免与子系统放进同一个存储的键冲突。 */
    private static final String STATE_PREFIX = "medical:sso:state:";
    private static final String BIND_PREFIX = "medical:sso:bind:";
    private static final String TICKET_PREFIX = "medical:sso:ticket:";

    /** 绑定时允许输错旧密码的次数。够容错，又不至于让接口能被用来慢慢猜密码。 */
    private static final int BIND_MAX_ATTEMPTS = 5;

    private final MedicalSsoProperties properties;
    private final MedicalSsoExchange exchange;
    private final MedicalSsoStateStore stateStore;
    private final MedicalIdentityBridge bridge;

    public MedicalSsoBridgeController(MedicalSsoProperties properties, MedicalSsoExchange exchange,
                                      MedicalSsoStateStore stateStore, MedicalIdentityBridge bridge) {
        this.properties = properties;
        this.exchange = exchange;
        this.stateStore = stateStore;
        this.bridge = bridge;
    }

    @GetMapping("/start")
    public void start(@RequestParam(defaultValue = "/") String redirect,
                      HttpServletResponse response) throws IOException {
        String state = MedicalSsoExchange.randomUrlSafe();
        MedicalSsoExchange.Attempt attempt = exchange.newAttempt(state);
        // state 与 code_verifier 一起暂存，回调时凭 state 取回；取出即删除，天然防重放。
        // 顺带记住用户原本要去的页面，登录完把他送回去而不是一律扔到首页。
        stateStore.save(STATE_PREFIX + state,
                encode(attempt.codeVerifier(), safeRedirect(redirect)),
                properties.getBridge().getStateTtl());
        response.sendRedirect(attempt.authorizationUrl());
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         HttpServletResponse response) throws IOException {
        if (error != null) {
            fail(response, "统一认证拒绝了本次登录：" + error);
            return;
        }
        if (code == null || state == null) {
            fail(response, "回调参数不完整");
            return;
        }
        String stored = stateStore.take(STATE_PREFIX + state);
        if (stored == null) {
            // state 对不上有两种可能：登录页开了太久已过期，或者这是一次重放
            fail(response, "登录已过期，请重新登录");
            return;
        }
        String[] parts = decode(stored);
        String codeVerifier = parts[0];
        String redirect = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "/";

        MedicalUser identity;
        try {
            identity = exchange.exchange(code, codeVerifier);
        } catch (RuntimeException ex) {
            fail(response, "换取身份失败，请重试");
            return;
        }

        String credential;
        try {
            credential = bridge.onAuthenticated(identity);
        } catch (IllegalArgumentException ex) {
            fail(response, ex.getMessage());
            return;
        }

        if (credential != null) {
            response.sendRedirect(front(properties.getBridge().getSuccessUri())
                    + "?ticket=" + issueTicket(credential)
                    + "&redirect=" + urlEncode(redirect));
            return;
        }

        // 身份没有关联到本系统的账号
        if (!properties.getBridge().isSelfServiceBinding()) {
            fail(response, "您的账号尚未在本系统关联，请联系管理员");
            return;
        }
        // 引导自助绑定：把这次已认证的身份暂存，等用户用旧账号确认是同一个人
        String bindTicket = MedicalSsoExchange.randomUrlSafe();
        long deadline = System.currentTimeMillis() + properties.getBridge().getStateTtl().toMillis();
        stateStore.save(BIND_PREFIX + bindTicket,
                serialize(identity) + "&" + urlEncode(String.valueOf(deadline))
                        + "&" + urlEncode(String.valueOf(BIND_MAX_ATTEMPTS)),
                properties.getBridge().getStateTtl());
        response.sendRedirect(front(properties.getBridge().getBindUri())
                + "?bindTicket=" + bindTicket
                + "&name=" + urlEncode(nullToEmpty(identity.name()))
                + "&redirect=" + urlEncode(redirect));
    }

    /** 前端用一次性票据换回凭证。凭证不直接放进地址，避免它进入浏览器历史与访问日志。 */
    @PostMapping("/exchange-ticket")
    public ResponseEntity<Map<String, Object>> exchangeTicket(@RequestBody TicketRequest request) {
        String credential = stateStore.take(TICKET_PREFIX + nullToEmpty(request.ticket()));
        if (credential == null) {
            return ResponseEntity.status(410).body(message("登录票据已失效，请重新登录"));
        }
        return ResponseEntity.ok(single("credential", credential));
    }

    /**
     * 首次绑定。走到这里说明统一认证已经通过，这一步只确认
     * 「统一身份里的这个人」和「本系统里的这个旧账号」是同一个人。
     */
    @PostMapping("/bind")
    public ResponseEntity<Map<String, Object>> bind(@RequestBody BindRequest request) {
        if (!properties.getBridge().isSelfServiceBinding()) {
            return ResponseEntity.badRequest().body(message("本系统未开启账号自助绑定"));
        }
        String key = BIND_PREFIX + nullToEmpty(request.bindTicket());
        String stored = stateStore.take(key);
        if (stored == null) {
            return ResponseEntity.status(410).body(message("绑定已过期，请重新登录后再试"));
        }
        String[] parts = decode(stored);
        long deadline = Long.parseLong(parts[parts.length - 2]);
        int attemptsLeft = Integer.parseInt(parts[parts.length - 1]);
        MedicalUser identity = deserialize(stored);
        try {
            String credential = bridge.bind(identity, request.username(), request.password());
            return ResponseEntity.ok(single("credential", credential));   // 成功后票据已消费，不再放回
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            // 旧密码打错一次就把票据作废，用户得从头再走一遍统一登录，这不合理。
            // 失败时把票据放回去继续用，但保留原有的截止时刻并扣减次数，
            // 免得这个接口变成可以慢慢猜别人旧密码的地方。
            long remain = deadline - System.currentTimeMillis();
            if (attemptsLeft > 1 && remain > 0) {
                stateStore.save(key, reissue(stored, deadline, attemptsLeft - 1),
                        Duration.ofMillis(remain));
            }
            return ResponseEntity.badRequest().body(message(ex.getMessage()));
        }
    }

    private String issueTicket(String credential) {
        String ticket = MedicalSsoExchange.randomUrlSafe();
        // 票据只在一次跳转之间存活，60 秒足够
        stateStore.save(TICKET_PREFIX + ticket, credential, Duration.ofSeconds(60));
        return ticket;
    }

    private void fail(HttpServletResponse response, String reason) throws IOException {
        response.sendRedirect(front(properties.getBridge().getFailureUri()) + "?reason=" + urlEncode(reason));
    }

    private String front(String path) {
        String base = properties.getBridge().getBaseUrl();
        if (base == null || base.trim().isEmpty()) {
            return path;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return UriComponentsBuilder.fromUriString(trimmed + path).build().toUriString();
    }

    /** 只接受站内路径，挡掉把用户带去外部站点的开放重定向。 */
    private static String safeRedirect(String redirect) {
        if (redirect == null || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/";
        }
        return redirect;
    }

    // 字段各自 URL 编码后用 & 连接，避免分隔符与姓名等内容冲突

    private static String encode(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(urlEncode(nullToEmpty(value)));
        }
        return builder.toString();
    }

    private static String[] decode(String stored) {
        String[] raw = stored.split("&", -1);
        String[] values = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            values[i] = decodeUtf8(raw[i]);
        }
        return values;
    }

    private static String serialize(MedicalUser identity) {
        return encode(identity.subject(), identity.personId(), identity.employeeNo(),
                identity.username(), identity.name(), identity.organizationCode(),
                identity.departmentCode());
    }

    /** 失败后把票据原样放回，只改剩余次数，截止时刻保持不变。 */
    private static String reissue(String stored, long deadline, int attemptsLeft) {
        String[] f = decode(stored);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < f.length - 2; i++) {
            builder.append(urlEncode(nullToEmpty(f[i]))).append('&');
        }
        return builder.append(urlEncode(String.valueOf(deadline)))
                .append('&').append(urlEncode(String.valueOf(attemptsLeft))).toString();
    }

    private static MedicalUser deserialize(String value) {
        String[] f = decode(value);
        return new MedicalUser(blankToNull(f[0]), blankToNull(f[1]), blankToNull(f[2]),
                blankToNull(f[3]), blankToNull(f[4]), blankToNull(f[5]), blankToNull(f[6]),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptySet(), Collections.<String>emptySet());
    }

    private static String urlEncode(String value) {
        return encodeUtf8(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String encodeUtf8(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("当前 JVM 不支持 UTF-8", ex);
        }
    }

    private static String decodeUtf8(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("当前 JVM 不支持 UTF-8", ex);
        }
    }

    private static Map<String, Object> message(String text) {
        return single("message", text);
    }

    private static Map<String, Object> single(String key, String value) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put(key, value);
        return body;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 绑定请求体。Java 8 没有 record，用普通类并保留同名访问方法。 */
    public static final class BindRequest {
        private String bindTicket;
        private String username;
        private String password;

        public BindRequest() {
        }

        public BindRequest(String bindTicket, String username, String password) {
            this.bindTicket = bindTicket;
            this.username = username;
            this.password = password;
        }

        public String bindTicket() { return bindTicket; }
        public String username() { return username; }
        public String password() { return password; }

        public void setBindTicket(String bindTicket) { this.bindTicket = bindTicket; }
        public void setUsername(String username) { this.username = username; }
        public void setPassword(String password) { this.password = password; }
    }

    public static final class TicketRequest {
        private String ticket;

        public TicketRequest() {
        }

        public TicketRequest(String ticket) {
            this.ticket = ticket;
        }

        public String ticket() { return ticket; }

        public void setTicket(String ticket) { this.ticket = ticket; }
    }
}
