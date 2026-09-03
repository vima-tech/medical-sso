package com.medical.union.portal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 登录失败后的处理。
 *
 * <p>本平台只有一个身份源，用户没有「选哪个登录方式」的余地，因此不应该看到
 * 框架自带的那张 OAuth2 选择页。而且这里的失败大多是**可恢复**的：
 * 浏览器恢复了停在回调地址的旧标签页、用户刷新或后退到回调地址、平台重启后
 * 会话里的授权请求没了——这些情况下 state 对不上，重新发起一次授权就好了，
 * 不该让用户对着一句英文的 Invalid credentials 自己琢磨。
 *
 * <p>所以：可恢复的自动重来一次，重来还失败（说明不是偶发）或本来就不可恢复的，
 * 才转到自家的中文提示页。
 */
public class LoginRetryFailureHandler implements AuthenticationFailureHandler {

    /** 自动重试的标记。带上它说明这次已经是重来的一次了，再失败就不再重来，避免死循环。 */
    private static final String RETRY_COOKIE = "MEDICAL_SSO_RETRY";

    /**
     * 这些错误换一次新的授权请求就能好。
     *
     * <p>{@code authorization_request_not_found} / {@code invalid_state_parameter}：
     * 回调带来的 state 在会话里找不到。{@code invalid_grant}：授权码过期或被重复使用。
     */
    private static final Set<String> RECOVERABLE = Set.of(
            "authorization_request_not_found", "invalid_state_parameter", "invalid_grant");

    private final String authorizationUri;

    public LoginRetryFailureHandler(String authorizationUri) {
        this.authorizationUri = authorizationUri;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : "";

        if (RECOVERABLE.contains(code) && !alreadyRetried(request)) {
            markRetried(response);
            response.sendRedirect(authorizationUri);
            return;
        }

        clearRetryMark(response);
        response.sendRedirect("/login-failed?reason=" + urlEncode(describe(code)));
    }

    /** 把协议错误码翻成一句管理员看得懂的话。 */
    private static String describe(String code) {
        return switch (code) {
            case "access_denied" -> "统一认证拒绝了本次登录，请确认账号是否已被停用";
            case "authorization_request_not_found", "invalid_state_parameter" ->
                    "登录请求已失效，请重新发起登录";
            case "invalid_grant" -> "登录凭据已过期，请重新登录";
            case "invalid_token", "invalid_id_token" -> "身份令牌校验未通过，请联系平台管理员";
            case "" -> "登录未能完成，请重试";
            default -> "登录未能完成（" + code + "），请重试或联系平台管理员";
        };
    }

    private static boolean alreadyRetried(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        for (Cookie cookie : request.getCookies()) {
            if (RETRY_COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void markRetried(HttpServletResponse response) {
        Cookie cookie = new Cookie(RETRY_COOKIE, "1");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        // 只在这一次跳转期间有效，让下一次正常登录不受影响
        cookie.setMaxAge(60);
        response.addCookie(cookie);
    }

    private static void clearRetryMark(HttpServletResponse response) {
        Cookie cookie = new Cookie(RETRY_COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
