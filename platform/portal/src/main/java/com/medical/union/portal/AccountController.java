package com.medical.union.portal;

import com.medical.union.portal.admin.AuditCenter;
import com.medical.union.portal.admin.IdentityRegistry;
import com.medical.union.portal.admin.KeycloakAdminClient;
import com.medical.union.portal.admin.OrganizationDirectory;
import com.medical.union.sso.MedicalRoleExtractor;
import com.medical.union.sso.MedicalUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账号与安全。本人查看自己的身份、改密码、管理登录设备。
 *
 * <p>这一页取代了 Keycloak 自带的账户控制台。不用它有三个理由：
 * 一是它是 Keycloak 品牌的界面，与本平台「认证内核藏在后台、不是运维入口」的定位矛盾；
 * 二是它把群组、应用列表这些平台刻意隐藏的概念直接摊给医护看；
 * 三是它允许本人改「名/姓」，而本平台姓名的真源是 full_name 属性、firstName 只是
 * 搜索镜像——本人在那里改完，平台显示的姓名不变、按姓名搜索却会错乱。
 *
 * <p>所以这里的身份信息一律只读：工号、机构、科室来自人事档案，由管理员在管理平台维护。
 */
@Controller
public class AccountController {

    /**
     * 触发 Keycloak 的「应用发起动作」改密码。
     *
     * <p>把这个参数带进授权请求，认证内核会先要求重新验证身份，再显示改密码页，
     * 且用的是本项目自己的中文登录主题（login-update-password.ftl）。
     * 旧密码校验、密码策略、失败锁定全部由认证内核负责，门户不接触任何口令。
     */
    public static final String UPDATE_PASSWORD_ACTION = "UPDATE_PASSWORD";

    private static final String CHANGE_PASSWORD_URI =
            "/oauth2/authorization/medical-sso?kc_action=" + UPDATE_PASSWORD_ACTION;

    /** Keycloak 自带角色，不是业务身份，不展示给本人。 */
    private static final Set<String> BUILT_IN_ROLES = Set.of("offline_access", "uma_authorization");

    private final MedicalUserMapper userMapper;
    private final boolean subsystemAdminEnabled;
    private final ObjectProvider<AuditCenter> audit;
    private final ObjectProvider<KeycloakAdminClient> admin;
    private final ObjectProvider<OrganizationDirectory> organizationDirectory;
    private final ObjectProvider<IdentityRegistry> identities;

    public AccountController(
            MedicalUserMapper userMapper,
            @Value("${portal.admin.enabled:true}") boolean subsystemAdminEnabled,
            ObjectProvider<AuditCenter> audit,
            ObjectProvider<KeycloakAdminClient> admin,
            ObjectProvider<OrganizationDirectory> organizationDirectory,
            ObjectProvider<IdentityRegistry> identities) {
        this.userMapper = userMapper;
        this.subsystemAdminEnabled = subsystemAdminEnabled;
        this.audit = audit;
        this.admin = admin;
        this.organizationDirectory = organizationDirectory;
        this.identities = identities;
    }

    @GetMapping("/account")
    String account(@AuthenticationPrincipal OidcUser principal, Model model) {
        Map<String, Object> claims = principal.getClaims();
        AuditCenter center = audit.getIfAvailable();
        model.addAttribute("medicalUser", userMapper.fromClaims(claims));
        model.addAttribute("orgNames", organizationNames());
        model.addAttribute("identityLabels", identityLabels(claims));
        model.addAttribute("sessions", center == null ? List.of() : center.sessions(principal.getSubject()));
        // 管理功能关掉时取不到 Admin API，会话列表就不展示，但改密码仍然可用。
        model.addAttribute("sessionsAvailable", center != null);
        model.addAttribute("changePasswordUri", CHANGE_PASSWORD_URI);
        model.addAttribute("subsystemAdmin", isPlatformAdmin(claims));
        return "account";
    }

    /**
     * 退出全部设备。
     *
     * <p>认证内核的注销接口作用于该人员的所有会话，当前这台也在内——所以按钮明说
     * 「含当前设备」。注销后必须把门户本地会话一并作废：认证内核那边已经失效，
     * 本地 Cookie 若还留着，这台机器会继续用旧登录态浏览门户页面。
     * 怀疑账号被别人用了的时候，这正是想要的效果。
     */
    @PostMapping("/account/logout-everywhere")
    String logoutEverywhere(@AuthenticationPrincipal OidcUser principal,
                            HttpServletRequest request,
                            RedirectAttributes redirect) {
        KeycloakAdminClient client = admin.getIfAvailable();
        if (client == null) {
            redirect.addFlashAttribute("error", "当前部署未开启该功能，请联系信息科管理员。");
            return "redirect:/account";
        }
        client.logoutUser(principal.getSubject());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/";
    }

    private Map<String, String> organizationNames() {
        OrganizationDirectory directory = organizationDirectory.getIfAvailable();
        return directory == null ? Map.of() : directory.nameByCode();
    }

    /**
     * 本人持有的通用身份，取管理平台维护的中文名。
     *
     * <p>取不到目录时退回显示角色标识本身，至少不是空白。
     */
    private Map<String, String> identityLabels(Map<String, Object> claims) {
        Map<String, String> labels = new LinkedHashMap<>();
        IdentityRegistry registry = identities.getIfAvailable();
        Map<String, String> byName = registry == null ? Map.of() : registry.list().stream()
                .collect(LinkedHashMap::new, (m, i) -> m.put(i.name(), i.label()), LinkedHashMap::putAll);
        MedicalRoleExtractor.realmRoles(claims).stream()
                .filter(role -> !role.startsWith("default-roles"))
                .filter(role -> !BUILT_IN_ROLES.contains(role))
                .sorted()
                .forEach(role -> labels.put(role, byName.getOrDefault(role, role)));
        return labels;
    }

    private boolean isPlatformAdmin(Map<String, Object> claims) {
        return subsystemAdminEnabled && MedicalRoleExtractor.realmRoles(claims).contains("sso-platform-admin");
    }
}
