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

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 人员管理。管理员在这里完成日常全部人员操作，不需要打开 Keycloak 控制台。
 */
@Controller
@ConditionalOnSubsystemAdmin
@RequestMapping("/admin/staff")
public class StaffAdminController {

    private static final int PAGE_SIZE = 20;

    private final StaffRegistry registry;
    private final OrganizationDirectory organizations;
    private final StaffImporter importer;
    private final AuditCenter audit;

    public StaffAdminController(StaffRegistry registry, OrganizationDirectory organizations,
                                StaffImporter importer, AuditCenter audit) {
        this.registry = registry;
        this.organizations = organizations;
        this.importer = importer;
        this.audit = audit;
    }

    @GetMapping
    String list(@RequestParam(required = false) String keyword,
                @RequestParam(defaultValue = "0") int page,
                Model model) {
        int current = Math.max(page, 0);
        List<Staff> staff = registry.search(keyword, current, PAGE_SIZE);
        int total = registry.count(keyword);
        model.addAttribute("staff", staff);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("page", current);
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("total", total);
        model.addAttribute("hasNext", (current + 1) * PAGE_SIZE < total);
        model.addAttribute("activeNav", "staff");
        return "admin/staff";
    }

    @GetMapping("/new")
    String createForm(Model model) {
        StaffForm form = new StaffForm();
        form.setEnabled(true);
        form.setMustChangePassword(true);
        model.addAttribute("form", form);
        addOptions(model);
        return "admin/staff-form";
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable String id, Model model) {
        Staff staff = registry.find(id);
        if (staff == null) {
            return "redirect:/admin/staff";
        }
        StaffForm form = new StaffForm();
        form.setId(staff.id());
        form.setUsername(staff.username());
        form.setName(staff.name());
        form.setPersonId(staff.personId());
        form.setEmployeeNo(staff.employeeNo());
        form.setOrganizationCode(staff.organizationCode());
        form.setDepartmentCode(staff.departmentCode());
        form.setAdditionalDepartmentCodes(staff.additionalDepartmentCodes());
        form.setEnabled(staff.enabled());
        form.setGeneralRoles(staff.generalRoles());
        form.setAccessibleSystems(staff.accessibleSystems());
        model.addAttribute("form", form);
        model.addAttribute("sessions", audit.sessions(id));
        addOptions(model);
        return "admin/staff-form";
    }

    @PostMapping
    String save(@Valid @ModelAttribute("form") StaffForm form,
                BindingResult binding,
                Model model,
                RedirectAttributes redirect) {
        if (form.isNew() && (form.getInitialPassword() == null || form.getInitialPassword().isBlank())) {
            binding.rejectValue("initialPassword", "required", "请设置初始密码");
        }
        if (binding.hasErrors()) {
            addOptions(model);
            return "admin/staff-form";
        }
        try {
            if (form.isNew()) {
                registry.create(form);
                redirect.addFlashAttribute("notice", "已新增人员 " + form.getName());
            } else {
                registry.update(form);
                redirect.addFlashAttribute("notice", "已保存 " + form.getName());
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            binding.reject("save", ex.getMessage());
            addOptions(model);
            return "admin/staff-form";
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/{id}/password")
    String resetPassword(@PathVariable String id,
                         @RequestParam String password,
                         @RequestParam(defaultValue = "false") boolean mustChange,
                         RedirectAttributes redirect) {
        if (password == null || password.isBlank()) {
            redirect.addFlashAttribute("error", "新密码不能为空");
            return "redirect:/admin/staff/" + id + "/edit";
        }
        registry.resetPassword(id, password, mustChange);
        redirect.addFlashAttribute("notice", "密码已重置");
        return "redirect:/admin/staff/" + id + "/edit";
    }

    @PostMapping("/{id}/logout")
    String forceLogout(@PathVariable String id, RedirectAttributes redirect) {
        registry.forceLogout(id);
        redirect.addFlashAttribute("notice", "已强制该人员在所有设备上下线");
        return "redirect:/admin/staff/" + id + "/edit";
    }

    @PostMapping("/{id}/enabled")
    String toggleEnabled(@PathVariable String id,
                         @RequestParam boolean enabled,
                         RedirectAttributes redirect) {
        registry.setEnabled(id, enabled);
        redirect.addFlashAttribute("notice", enabled ? "已启用该人员" : "已停用该人员，其登录会话同时被注销");
        return "redirect:/admin/staff";
    }

    @GetMapping("/import")
    String importForm(Model model) {
        model.addAttribute("headers", StaffImporter.HEADERS);
        model.addAttribute("activeNav", "staff");
        return "admin/staff-import";
    }

    /** 下载导入模板。带 BOM 以便 Excel 直接双击打开不乱码。 */
    @GetMapping("/import/template")
    ResponseEntity<byte[]> template() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = importer.template().getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(body, 0, content, bom.length, body.length);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"staff-template.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content);
    }

    @PostMapping("/import")
    String doImport(@RequestParam("file") MultipartFile file,
                    @RequestParam(defaultValue = "false") boolean mustChangePassword,
                    Model model) {
        model.addAttribute("headers", StaffImporter.HEADERS);
        model.addAttribute("activeNav", "staff");
        if (file == null || file.isEmpty()) {
            model.addAttribute("error", "请选择要导入的文件");
            return "admin/staff-import";
        }
        try {
            model.addAttribute("result", importer.importFrom(file.getInputStream(), mustChangePassword));
        } catch (IOException ex) {
            model.addAttribute("error", "读取文件失败：" + ex.getMessage());
        }
        return "admin/staff-import";
    }

    private void addOptions(Model model) {
        model.addAttribute("organizations", organizations.organizations());
        model.addAttribute("generalRoles", registry.generalRoles());
        model.addAttribute("systems", registry.systems());
        model.addAttribute("activeNav", "staff");
    }
}
