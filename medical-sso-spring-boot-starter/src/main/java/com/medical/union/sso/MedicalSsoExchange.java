package com.medical.union.sso;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * 与统一认证之间的协议细节：拼授权地址、用授权码换令牌、校验身份令牌。
 *
 * <p>子系统不需要理解这些，接入时只面对 {@link MedicalIdentityBridge}。
 */
public class MedicalSsoExchange {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final MedicalSsoProperties properties;
    private final MedicalUserMapper userMapper;
    private final JwtDecoder idTokenDecoder;
    private final RestClient http;

    public MedicalSsoExchange(MedicalSsoProperties properties, MedicalUserMapper userMapper,
                              JwtDecoder idTokenDecoder, RestClient.Builder builder) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.idTokenDecoder = idTokenDecoder;
        this.http = builder.build();
    }

    /** 新建一次登录尝试，返回要跳转的授权地址与需要暂存的 code_verifier。 */
    public Attempt newAttempt(String state) {
        String codeVerifier = randomUrlSafe();
        String url = UriComponentsBuilder.fromUriString(issuer() + "/protocol/openid-connect/auth")
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getBridge().resolvedRedirectUri())
                .queryParam("scope", "openid profile")
                .queryParam("state", state)
                .queryParam("code_challenge", sha256(codeVerifier))
                .queryParam("code_challenge_method", "S256")
                // 必须显式编码：scope 里的空格和 redirect_uri 里的 :// 不编码会构成畸形地址
                .encode()
                .toUriString();
        return new Attempt(url, codeVerifier);
    }

    /**
     * 用授权码换取身份。这一步是后端直连统一认证，浏览器看不到，
     * 令牌因此不会出现在地址栏、浏览器历史和访问日志里。
     */
    public MedicalUser exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.getBridge().resolvedRedirectUri());
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getBridge().getClientSecret());
        form.add("code_verifier", codeVerifier);

        Map<?, ?> response;
        try {
            response = http.post()
                    .uri(issuer() + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("换取身份失败：" + ex.getResponseBodyAsString(), ex);
        }
        if (response == null || response.get("id_token") == null) {
            throw new IllegalStateException("换取身份失败：统一认证未返回身份令牌");
        }
        // 验签、校验签发者与有效期都由解码器完成，失败直接抛出
        return userMapper.fromClaims(idTokenDecoder.decode(String.valueOf(response.get("id_token"))).getClaims());
    }

    private String issuer() {
        String issuer = properties.getBridge().getIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("未配置 medical.sso.bridge.issuer-uri");
        }
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    public static String randomUrlSafe() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    /** 一次登录尝试：要跳转的地址，以及需要暂存到回调时才用的 code_verifier。 */
    public record Attempt(String authorizationUrl, String codeVerifier) {
    }
}
