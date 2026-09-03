package com.medical.union.demo.legacy;

import com.medical.union.sso.MedicalSsoBridgeController;
import com.medical.union.sso.MedicalSsoProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Optional;

/**
 * 子系统原有的登录与会话。接入统一身份后这些代码基本没动，
 * 只是登录页多了一个「统一身份登录」入口，本地账号收进「其他登录方式」。
 *
 * <p>本演示是服务端渲染，所以直接调用接入组件的端点方法；
 * 前后端分离的系统由前端向 {@code /api/auth/sso/exchange-ticket}
 * 和 {@code /api/auth/sso/bind} 发两个请求，效果相同。
 */
@Controller
public class LegacyLoginController {

    private static final String COOKIE = "LEGACY_TOKEN";

    private final LegacyAccountStore accounts;
    private final MedicalSsoProperties properties;
    private final MedicalSsoBridgeController sso;

    public LegacyLoginController(LegacyAccountStore accounts, MedicalSsoProperties properties,
                                 MedicalSsoBridgeController sso) {
        this.accounts = accounts;
        this.properties = properties;
        this.sso = sso;
    }

    @GetMapping("/login")
    String loginPage(@RequestParam(required = false) String reason, Model model) {
        return renderLogin(model, reason);
    }

    /** 原有的账号密码登录，逻辑与接入前一致。 */
    @PostMapping("/login")
    String login(@RequestParam String username, @RequestParam String password,
                 HttpServletResponse response, Model model) {
        Optional<LegacyAccount> account = accounts.byUsername(username)
                .filter(candidate -> candidate.passwordMatches(password));
        if (account.isEmpty()) {
            return renderLogin(model, "账号或密码错误");
        }
        MedicalSsoProperties.Bridge.LocalLogin mode = properties.getBridge().getLocalLogin();
        if (mode == MedicalSsoProperties.Bridge.LocalLogin.DISABLED
                || (mode == MedicalSsoProperties.Bridge.LocalLogin.EMERGENCY_ONLY && !account.get().isEmergency())) {
            return renderLogin(model, "本系统已改用统一身份登录，请用上方入口");
        }
        setToken(response, accounts.issueToken(account.get()));
        return "redirect:/";
    }

    /** 统一身份登录成功后回到这里，用一次性票据换回本系统的凭证。 */
    @GetMapping("/sso/callback")
    String ssoCallback(@RequestParam String ticket, @RequestParam(defaultValue = "/") String redirect,
                       HttpServletResponse response, Model model) {
        ResponseEntity<Map<String, Object>> result =
                sso.exchangeTicket(new MedicalSsoBridgeController.TicketRequest(ticket));
        if (!result.getStatusCode().is2xxSuccessful()) {
            return renderLogin(model, message(result, "登录票据已失效，请重新登录"));
        }
        setToken(response, String.valueOf(result.getBody().get("credential")));
        return "redirect:" + safe(redirect);
    }

    /** 首次绑定页：统一认证已通过，请用旧账号确认是同一个人。 */
    @GetMapping("/sso/bind")
    String bindPage(@RequestParam String bindTicket, @RequestParam(required = false) String name,
                    @RequestParam(defaultValue = "/") String redirect, Model model) {
        model.addAttribute("bindTicket", bindTicket);
        model.addAttribute("name", name);
        model.addAttribute("redirect", redirect);
        return "bind";
    }

    @PostMapping("/sso/bind")
    String bind(@RequestParam String bindTicket, @RequestParam String username,
                @RequestParam String password, @RequestParam(defaultValue = "/") String redirect,
                HttpServletResponse response, Model model) {
        ResponseEntity<Map<String, Object>> result = sso.bind(
                new MedicalSsoBridgeController.BindRequest(bindTicket, username, password));
        if (!result.getStatusCode().is2xxSuccessful()) {
            model.addAttribute("bindTicket", bindTicket);
            model.addAttribute("redirect", redirect);
            model.addAttribute("reason", message(result, "绑定失败"));
            return "bind";
        }
        setToken(response, String.valueOf(result.getBody().get("credential")));
        return "redirect:" + safe(redirect);
    }

    @PostMapping("/logout")
    String logout(HttpServletRequest request, HttpServletResponse response) {
        readToken(request).ifPresent(accounts::revoke);
        Cookie cookie = new Cookie(COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return "redirect:/login";
    }

    private String renderLogin(Model model, String reason) {
        model.addAttribute("reason", reason);
        model.addAttribute("localLogin", properties.getBridge().getLocalLogin().name());
        return "login";
    }

    private static String message(ResponseEntity<Map<String, Object>> result, String fallback) {
        Object message = result.getBody() == null ? null : result.getBody().get("message");
        return message == null ? fallback : String.valueOf(message);
    }

    private static String safe(String redirect) {
        return redirect.startsWith("/") && !redirect.startsWith("//") ? redirect : "/";
    }

    private void setToken(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    static Optional<String> readToken(HttpServletRequest request) {
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
}
