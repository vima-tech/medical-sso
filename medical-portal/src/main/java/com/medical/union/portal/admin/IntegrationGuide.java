package com.medical.union.portal.admin;

import java.util.List;

/**
 * 某个子系统专属的对接文档：所有片段都已填好真实的 client-id、issuer 和回调地址，
 * 复制到子系统即可运行，不需要再改占位符。
 */
public record IntegrationGuide(
        String clientId,
        String name,
        String stack,
        String stackLabel,
        String issuerUri,
        String baseUrl,
        String redirectUri,
        String clientSecret,
        boolean secretVisible,
        List<Snippet> snippets) {

    public record Snippet(String title, String language, String filename, String hint, String content) {
    }

    public boolean isBoot2() {
        return SubsystemForm.Stack.BOOT2.equals(stack);
    }

    public boolean isBoot3() {
        return SubsystemForm.Stack.BOOT3.equals(stack);
    }

    public boolean isBridge() {
        return SubsystemForm.Stack.BRIDGE.equals(stack);
    }

    public boolean isGateway() {
        return SubsystemForm.Stack.GATEWAY.equals(stack);
    }
}
