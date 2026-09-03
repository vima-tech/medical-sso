package com.medical.union.portal.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 管理平台首页：一屏看清平台现状。
 *
 * <p>这里原本直接跳到人员管理。问题是管理员一进来就落在一张人员列表上，
 * 「这个平台现在有几个机构、接了几个系统、最近有没有人反复登不进去」全都看不到，
 * 要靠逐个点进各模块去凑。首页因此只回答两个问题：现状是什么、有没有要处理的。
 */
@Controller
@ConditionalOnSubsystemAdmin
public class AdminLandingController {

    /** 首页只列最近几条登录失败，多了就该去审计记录里查。 */
    private static final int RECENT_FAILURES = 5;

    private final OrganizationDirectory organizations;
    private final StaffRegistry staff;
    private final SubsystemRegistry subsystems;
    private final IdentityRegistry identities;
    private final AuditCenter audit;

    public AdminLandingController(OrganizationDirectory organizations, StaffRegistry staff,
                                  SubsystemRegistry subsystems, IdentityRegistry identities,
                                  AuditCenter audit) {
        this.organizations = organizations;
        this.staff = staff;
        this.subsystems = subsystems;
        this.identities = identities;
        this.audit = audit;
    }

    @GetMapping("/admin")
    String overview(Model model) {
        List<OrganizationOption> orgs = organizations.organizations();
        List<Subsystem> systems = subsystems.list();
        List<AuditCenter.Entry> failures = audit.loginEvents("LOGIN_ERROR", RECENT_FAILURES);

        model.addAttribute("organizationCount", orgs.size());
        model.addAttribute("departmentCount",
                orgs.stream().mapToInt(org -> org.departments().size()).sum());
        model.addAttribute("staffCount", staff.count(null));
        model.addAttribute("subsystemCount", systems.size());
        // 停用的系统登记着却进不去，属于「登记了但没生效」，值得在首页点出来
        model.addAttribute("disabledSubsystemCount",
                systems.stream().filter(system -> !system.enabled()).count());
        model.addAttribute("identityCount", identities.list().size());
        model.addAttribute("recentFailures", failures);
        model.addAttribute("activeNav", "overview");
        return "admin/overview";
    }
}
