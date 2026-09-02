package com.medical.union.portal.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** 授权中心：一屏看清谁能进哪些业务系统，并支持整列批量授权。 */
@Controller
@ConditionalOnSubsystemAdmin
@RequestMapping("/admin/authorizations")
public class AuthorizationAdminController {

    private final AuthorizationCenter center;
    private final OrganizationRegistry organizations;

    public AuthorizationAdminController(AuthorizationCenter center, OrganizationRegistry organizations) {
        this.center = center;
        this.organizations = organizations;
    }

    @GetMapping
    String matrix(@RequestParam(required = false) String departmentId, Model model) {
        model.addAttribute("matrix", center.matrix(departmentId));
        model.addAttribute("organizations", organizations.tree());
        model.addAttribute("departmentId", departmentId == null ? "" : departmentId);
        model.addAttribute("activeNav", "authorizations");
        return "admin/authorizations";
    }

    @PostMapping
    String save(@RequestParam(required = false) String departmentId,
                @RequestParam(name = "visible", required = false) List<String> visible,
                @RequestParam(name = "granted", required = false) List<String> granted,
                RedirectAttributes redirect) {
        int changed = center.apply(visible == null ? List.of() : visible, granted);
        redirect.addFlashAttribute("notice", changed == 0 ? "没有需要调整的授权" : "已调整 " + changed + " 条授权");
        if (departmentId != null && !departmentId.isBlank()) {
            redirect.addAttribute("departmentId", departmentId);
        }
        return "redirect:/admin/authorizations";
    }
}
