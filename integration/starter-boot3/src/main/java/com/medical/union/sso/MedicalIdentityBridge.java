package com.medical.union.sso;

/**
 * 子系统接入统一身份的唯一必需实现。
 *
 * <p>统一认证只负责确认「你是谁」，子系统仍然用自己原有的会话或令牌机制维持登录态。
 * 这个接口就是两者的交接点：统一认证把身份交给你，你按自己的方式发一张本系统的凭证。
 *
 * <p>这样设计是为了不动子系统既有的鉴权体系——原有的过滤器、权限缓存、在线用户、
 * 强制下线全部照旧工作，只是「凭证怎么来的」换了一条路径。
 */
public interface MedicalIdentityBridge {

    /**
     * 统一身份认证已通过，请返回本系统的登录凭证。
     *
     * @param identity 统一身份，其中 {@code personId} 是跨系统关联的锚点
     * @return 本系统的登录凭证（令牌、会话 id 等），由前端后续携带；
     *         返回 {@code null} 表示这个身份还没有关联到本系统的账号，
     *         平台会转入账号绑定流程（若已开启），否则提示用户联系管理员
     */
    String onAuthenticated(MedicalUser identity);

    /**
     * 账号自助绑定：用本系统原有的账号密码验证一次，通过后建立与统一身份的关联。
     *
     * <p>只有在 {@link #onAuthenticated} 返回 {@code null} 后才会走到这里，
     * 也就是说调用方已经通过了统一认证，这一步只是确认「统一身份里的这个人」
     * 和「本系统里的这个旧账号」是同一个人。
     *
     * <p>实现时务必做两件事：校验旧密码；把 {@code identity.personId()} 写进本地账号。
     * 不要按姓名或手机号自动匹配——重名在医院很常见，匹配错的代价是把一个人的
     * 数据权限给了另一个人。
     *
     * @return 绑定成功后本系统的登录凭证
     * @throws IllegalArgumentException 旧账号或密码不正确、账号已停用、该账号已被他人绑定
     */
    default String bind(MedicalUser identity, String username, String password) {
        throw new UnsupportedOperationException("本系统未开启账号自助绑定");
    }
}
