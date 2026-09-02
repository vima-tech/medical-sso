package com.medical.union.portal.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 审计中心：登录记录与管理操作记录。 */
@Controller
@ConditionalOnSubsystemAdmin
@RequestMapping("/admin/audit")
public class AuditAdminController {

    private static final int MAX = 200;

    private final AuditCenter audit;

    public AuditAdminController(AuditCenter audit) {
        this.audit = audit;
    }

    @GetMapping
    String view(@RequestParam(defaultValue = "login") String tab,
                @RequestParam(required = false) String type,
                Model model) {
        boolean adminTab = "admin".equals(tab);
        model.addAttribute("tab", adminTab ? "admin" : "login");
        model.addAttribute("type", type == null ? "" : type);
        model.addAttribute("entries", adminTab ? audit.adminEvents(MAX) : audit.loginEvents(type, MAX));
        model.addAttribute("activeNav", "audit");
        return "admin/audit";
    }
}
