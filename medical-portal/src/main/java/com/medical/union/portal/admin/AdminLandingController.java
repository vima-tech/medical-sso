package com.medical.union.portal.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 管理平台的入口，落到最高频的人员管理。 */
@Controller
@ConditionalOnSubsystemAdmin
public class AdminLandingController {

    @GetMapping("/admin")
    String landing() {
        return "redirect:/admin/staff";
    }
}
