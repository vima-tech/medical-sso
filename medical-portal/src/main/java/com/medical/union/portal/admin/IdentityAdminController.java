package com.medical.union.portal.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 通用身份维护。 */
@Controller
@ConditionalOnSubsystemAdmin
@RequestMapping("/admin/identities")
public class IdentityAdminController {

    private final IdentityRegistry registry;

    public IdentityAdminController(IdentityRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("identities", registry.list());
        model.addAttribute("activeNav", "identities");
        return "admin/identities";
    }

    @PostMapping
    String create(@RequestParam String name,
                  @RequestParam String label,
                  RedirectAttributes redirect) {
        try {
            registry.create(name, label);
            redirect.addFlashAttribute("notice", "已新增通用身份「" + label + "」");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/identities";
    }

    @PostMapping("/{name}/rename")
    String rename(@PathVariable String name,
                  @RequestParam String label,
                  RedirectAttributes redirect) {
        try {
            registry.rename(name, label);
            redirect.addFlashAttribute("notice", "已保存");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/identities";
    }

    @PostMapping("/{name}/delete")
    String delete(@PathVariable String name, RedirectAttributes redirect) {
        try {
            registry.delete(name);
            redirect.addFlashAttribute("notice", "已删除");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/identities";
    }
}
