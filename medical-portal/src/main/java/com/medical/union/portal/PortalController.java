package com.medical.union.portal;

import com.medical.union.portal.admin.ApplicationDirectory;
import com.medical.union.portal.admin.OrganizationDirectory;
import com.medical.union.sso.MedicalRoleExtractor;
import com.medical.union.sso.MedicalUserMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
public class PortalController {

    private final MedicalUserMapper userMapper;
    private final String accountUrl;
    private final boolean subsystemAdminEnabled;
    private final ObjectProvider<ApplicationDirectory> applicationDirectory;
    private final ObjectProvider<OrganizationDirectory> organizationDirectory;

    public PortalController(
            MedicalUserMapper userMapper,
            @Value("${portal.account-url}") String accountUrl,
            @Value("${portal.admin.enabled:true}") boolean subsystemAdminEnabled,
            ObjectProvider<ApplicationDirectory> applicationDirectory,
            ObjectProvider<OrganizationDirectory> organizationDirectory) {
        this.userMapper = userMapper;
        this.accountUrl = accountUrl;
        this.subsystemAdminEnabled = subsystemAdminEnabled;
        this.applicationDirectory = applicationDirectory;
        this.organizationDirectory = organizationDirectory;
    }

    /**
     * 把机构、科室编码换成中文名。
     *
     * <p>门户是普通医护每天看的页面，显示 H001、D001 对他们没有任何意义。
     * 管理功能关闭时取不到名称目录，此时退回显示编码，至少不是空白。
     */
    private Map<String, String> organizationNames() {
        OrganizationDirectory directory = organizationDirectory.getIfAvailable();
        return directory == null ? Map.of() : directory.nameByCode();
    }

    /**
     * 登录后的落地分流。
     *
     * <p>平台管理员直接进管理平台，业务人员进应用门户。管理员本来就没有业务系统权限，
     * 让他先看到一个空的「我的应用」既没用又像出了故障。
     */
    @GetMapping("/")
    String landing(@AuthenticationPrincipal OidcUser principal) {
        return isPlatformAdmin(principal) ? "redirect:/admin" : "redirect:/apps";
    }

    /** 应用门户。管理员可以从管理平台的「应用门户」入口进来。 */
    @GetMapping("/apps")
    String apps(@AuthenticationPrincipal OidcUser principal, Model model) {
        // 应用列表按本人实际拥有的系统访问权限动态生成，
        // 管理台登记新系统并授权后会自动出现，不需要改门户配置。
        ApplicationDirectory directory = applicationDirectory.getIfAvailable();
        List<ApplicationDirectory.Application> applications = directory == null
                ? List.of()
                : directory.forUser(principal.getClaims());

        model.addAttribute("medicalUser", userMapper.fromClaims(principal.getClaims()));
        model.addAttribute("orgNames", organizationNames());
        model.addAttribute("applications", applications);
        model.addAttribute("accountUrl", accountUrl);
        model.addAttribute("subsystemAdmin", isPlatformAdmin(principal));
        return "index";
    }

    private boolean isPlatformAdmin(OidcUser principal) {
        return subsystemAdminEnabled
                && MedicalRoleExtractor.realmRoles(principal.getClaims()).contains("sso-platform-admin");
    }
}
