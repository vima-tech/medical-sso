package com.medical.union.portal.admin;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 机构与科室维护。人员表单里的机构科室下拉就来自这里。
 */
@Controller
@ConditionalOnSubsystemAdmin
@RequestMapping("/admin/organizations")
public class OrganizationAdminController {

    private final OrganizationRegistry registry;

    public OrganizationAdminController(OrganizationRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    String tree(Model model) {
        model.addAttribute("organizations", registry.tree());
        model.addAttribute("activeNav", "organizations");
        return "admin/organizations";
    }

    @GetMapping("/new")
    String createForm(@RequestParam(required = false) String parentId, Model model) {
        OrgUnitForm form = new OrgUnitForm();
        form.setParentId(parentId);
        model.addAttribute("form", form);
        model.addAttribute("parentName", parentName(parentId));
        model.addAttribute("activeNav", "organizations");
        return "admin/organization-form";
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable String id, Model model) {
        OrgUnitForm form = registry.load(id);
        if (form == null) {
            return "redirect:/admin/organizations";
        }
        model.addAttribute("form", form);
        model.addAttribute("parentName", parentName(form.getParentId()));
        model.addAttribute("activeNav", "organizations");
        return "admin/organization-form";
    }

    @PostMapping
    String save(@Valid @ModelAttribute("form") OrgUnitForm form,
                BindingResult binding,
                Model model,
                RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                registry.save(form);
                redirect.addFlashAttribute("notice",
                        (form.isDepartment() ? "科室「" : "机构「") + form.getName() + "」已保存");
                return "redirect:/admin/organizations";
            } catch (IllegalArgumentException | IllegalStateException ex) {
                binding.reject("save", ex.getMessage());
            }
        }
        model.addAttribute("parentName", parentName(form.getParentId()));
        model.addAttribute("activeNav", "organizations");
        return "admin/organization-form";
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable String id, RedirectAttributes redirect) {
        try {
            registry.delete(id);
            redirect.addFlashAttribute("notice", "已删除");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/organizations";
    }

    private String parentName(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        OrgUnitForm parent = registry.load(parentId);
        return parent == null ? null : parent.getName();
    }
}
