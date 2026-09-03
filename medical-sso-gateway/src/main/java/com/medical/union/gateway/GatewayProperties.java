package com.medical.union.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 接入网关的配置。
 *
 * <p>面向的是「改不动的系统」：老 PHP、.NET、买来的成品、没人维护的项目。
 * 登录在网关上完成，业务系统只需要从请求头里读人员信息。
 */
@ConfigurationProperties("medical.gateway")
public class GatewayProperties {

    /** 业务系统的真实地址，例如 http://127.0.0.1:9000。网关把请求原样转给它。 */
    private String upstream;

    /** 网关对外地址，用户在浏览器里访问的就是它，例如 https://his.intra.example。 */
    private String publicBaseUrl;

    /**
     * 无需登录即可访问的路径前缀，例如静态资源、健康检查。
     * 默认全站都要登录——先关严，再按需要开口子。
     */
    private List<String> publicPaths = new ArrayList<>();

    /**
     * 网关与业务系统之间的共享口令，随请求以 X-Medical-Gateway-Token 头送出。
     *
     * <p>身份走请求头有个前提：业务系统只能接受网关转来的请求。
     * 能直连业务系统的人，可以自己伪造一个请求头冒充任何人。
     * 网络上要用防火墙或只监听回环把业务系统封起来，这个口令是第二道闸。
     */
    private String upstreamToken;

    /** 登录状态的有效期。 */
    private Duration sessionTtl = Duration.ofHours(8);

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public String getUpstreamToken() {
        return upstreamToken;
    }

    public void setUpstreamToken(String upstreamToken) {
        this.upstreamToken = upstreamToken;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    /** 去掉结尾斜杠的上游地址。 */
    public String resolvedUpstream() {
        if (upstream == null || upstream.isBlank()) {
            throw new IllegalStateException("请配置 medical.gateway.upstream 指向业务系统的真实地址");
        }
        return upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
    }

    public boolean isPublic(String path) {
        for (String prefix : publicPaths) {
            if (!prefix.isBlank() && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
