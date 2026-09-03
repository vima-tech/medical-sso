package com.medical.union.demo.legacy;

import com.medical.union.sso.MedicalIdentityBridge;
import com.medical.union.sso.MedicalUser;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 接入统一身份需要子系统写的全部代码，就是这一个类。
 *
 * <p>登录跳转、PKCE、state 防重放、换码、验签、回调端点、绑定票据，
 * 全部由接入组件承担；这里只回答两个问题：
 * 「拿到这个身份，我发什么凭证」和「这个身份对应我系统里的哪个账号」。
 */
@Component
public class LegacyIdentityBridge implements MedicalIdentityBridge {

    private final LegacyAccountStore accounts;

    public LegacyIdentityBridge(LegacyAccountStore accounts) {
        this.accounts = accounts;
    }

    @Override
    public String onAuthenticated(MedicalUser identity) {
        Optional<LegacyAccount> account = accounts.byPersonId(identity.personId());
        if (account.isEmpty()) {
            // 返回 null 表示还没关联，组件会转入自助绑定流程。
            // 不要在这里按姓名或工号猜测匹配——猜错等于把一个人的数据权限给了另一个人。
            return null;
        }
        return accounts.issueToken(account.get());
    }

    @Override
    public String bind(MedicalUser identity, String username, String password) {
        LegacyAccount account = accounts.byUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("原账号或密码不正确"));
        if (!account.passwordMatches(password)) {
            throw new IllegalArgumentException("原账号或密码不正确");
        }
        // 走到这里说明：统一认证已通过，且此人能拿出旧账号的密码，
        // 两个身份是同一个人由本人证明，不是系统猜的
        accounts.link(account, identity.personId());
        return accounts.issueToken(account);
    }
}
