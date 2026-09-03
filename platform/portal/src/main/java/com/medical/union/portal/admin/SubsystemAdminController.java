package com.medical.union.portal.admin;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 子系统登记与对接文档。登记后立刻给出该子系统专属的、可直接复制的接入代码。
 */
@Controller
@ConditionalOnSubsystemAdmin
@RequestMapping("/admin/subsystems")
public class SubsystemAdminController {

    private final SubsystemRegistry registry;
    private final IntegrationGuideFactory guides;
    private final SubsystemDiagnostics diagnostics;
    private final PortalAdminProperties properties;

    public SubsystemAdminController(SubsystemRegistry registry, IntegrationGuideFactory guides,
                                    SubsystemDiagnostics diagnostics, PortalAdminProperties properties) {
        this.registry = registry;
        this.guides = guides;
        this.diagnostics = diagnostics;
        this.properties = properties;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("subsystems", registry.list());
        return "admin/subsystems";
    }

    @GetMapping("/new")
    String form(Model model) {
        model.addAttribute("form", new SubsystemForm());
        return "admin/subsystem-form";
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") SubsystemForm form,
                  BindingResult binding,
                  RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "admin/subsystem-form";
        }
        String secret;
        try {
            secret = registry.register(form);
        } catch (IllegalArgumentException ex) {
            binding.rejectValue("code", "duplicate", ex.getMessage());
            return "admin/subsystem-form";
        }
        // Secret 只随本次跳转传递一次，不落库、不进日志
        redirect.addFlashAttribute("clientSecret", secret);
        redirect.addFlashAttribute("justCreated", true);
        return "redirect:/admin/subsystems/" + form.getCode() + "/guide?stack=" + form.getStack();
    }

    @GetMapping("/{clientId}")
    String detail(@PathVariable String clientId, Model model) {
        Subsystem subsystem = registry.find(clientId);
        if (subsystem == null) {
            return "redirect:/admin/subsystems";
        }
        SubsystemForm form = new SubsystemForm();
        form.setName(subsystem.name());
        form.setCode(subsystem.clientId());
        form.setBaseUrl(subsystem.baseUrl());
        form.setStack(subsystem.stack());
        model.addAttribute("subsystem", subsystem);
        model.addAttribute("form", form);
        model.addAttribute("activeNav", "subsystems");
        return "admin/subsystem-detail";
    }

    @PostMapping("/{clientId}")
    String update(@PathVariable String clientId,
                  @Valid @ModelAttribute("form") SubsystemForm form,
                  BindingResult binding,
                  Model model,
                  RedirectAttributes redirect) {
        form.setCode(clientId);
        if (!binding.hasFieldErrors("name") && !binding.hasFieldErrors("baseUrl")) {
            try {
                registry.update(form);
                redirect.addFlashAttribute("notice", "已保存");
                return "redirect:/admin/subsystems/" + clientId;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                binding.reject("save", ex.getMessage());
            }
        }
        model.addAttribute("subsystem", registry.find(clientId));
        model.addAttribute("activeNav", "subsystems");
        return "admin/subsystem-detail";
    }

    @PostMapping("/{clientId}/enabled")
    String toggle(@PathVariable String clientId,
                  @RequestParam boolean enabled,
                  RedirectAttributes redirect) {
        try {
            registry.setEnabled(clientId, enabled);
            redirect.addFlashAttribute("notice", enabled ? "已启用该业务系统" : "已停用该业务系统，本系统将无法登录");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/subsystems/" + clientId;
    }

    @PostMapping("/{clientId}/secret")
    String regenerateSecret(@PathVariable String clientId, RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("clientSecret", registry.regenerateSecret(clientId));
            redirect.addFlashAttribute("notice", "已重新生成 Secret，旧的立即失效，请及时更新该系统的配置");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/admin/subsystems/" + clientId;
        }
        return "redirect:/admin/subsystems/" + clientId + "/guide";
    }

    @PostMapping("/{clientId}/roles")
    String addRole(@PathVariable String clientId,
                   @RequestParam String roleName,
                   @RequestParam(required = false) String description,
                   RedirectAttributes redirect) {
        try {
            registry.addRole(clientId, roleName, description);
            redirect.addFlashAttribute("notice", "已新增角色 " + roleName);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/subsystems/" + clientId;
    }

    @PostMapping("/{clientId}/roles/{roleName}/delete")
    String removeRole(@PathVariable String clientId,
                      @PathVariable String roleName,
                      RedirectAttributes redirect) {
        try {
            registry.removeRole(clientId, roleName);
            redirect.addFlashAttribute("notice", "已删除角色 " + roleName);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/subsystems/" + clientId;
    }

    @PostMapping("/{clientId}/delete")
    String delete(@PathVariable String clientId, RedirectAttributes redirect) {
        try {
            registry.delete(clientId);
            redirect.addFlashAttribute("notice", "已删除业务系统 " + clientId);
            return "redirect:/admin/subsystems";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/admin/subsystems/" + clientId;
        }
    }

    @GetMapping("/{clientId}/check")
    String check(@PathVariable String clientId, Model model) {
        Subsystem subsystem = registry.find(clientId);
        if (subsystem == null) {
            return "redirect:/admin/subsystems";
        }
        model.addAttribute("subsystem", subsystem);
        model.addAttribute("report", diagnostics.run(clientId));
        model.addAttribute("activeNav", "subsystems");
        return "admin/subsystem-check";
    }

    @GetMapping("/{clientId}/guide")
    String guide(@PathVariable String clientId,
                 @RequestParam(required = false) String stack,
                 Model model) {
        Subsystem subsystem = registry.find(clientId);
        if (subsystem == null) {
            return "redirect:/admin/subsystems";
        }
        // 没有显式指定时，用登记时选的技术栈，而不是每次都默认一种
        if (stack == null || stack.isBlank()) {
            stack = subsystem.stack();
        }
        String secret = (String) model.asMap().get("clientSecret");
        model.addAttribute("guide", guides.create(subsystem, stack, secret));
        model.addAttribute("subsystem", subsystem);
        return "admin/subsystem-guide";
    }

    /**
     * 导出一份可离线分发的对接说明。
     *
     * <p>真正要照着配的是业务系统的开发方，而他们没有本平台账号、多半也不在内网。
     * 页面上的对接文档只有管理员看得到，管理员只能截图或口述转达，参数极易抄错。
     * 这里产出一个自包含的 HTML 文件：样式内联、无外链、双击即可阅读，也能直接打印成 PDF。
     *
     * <p>刻意不含 Client Secret——它在平台上只展示一次，而这份文件会在邮件和聊天工具里流转。
     */
    @GetMapping("/{clientId}/guide/export")
    String exportGuide(@PathVariable String clientId,
                       @RequestParam(required = false) String stack,
                       Model model,
                       HttpServletResponse response) {
        Subsystem subsystem = registry.find(clientId);
        if (subsystem == null) {
            return "redirect:/admin/subsystems";
        }
        if (stack == null || stack.isBlank()) {
            stack = subsystem.stack();
        }
        // 传 null 而不是真实 Secret：导出件里不留任何凭据
        model.addAttribute("guide", guides.create(subsystem, stack, null));
        model.addAttribute("generatedAt",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(properties.getTimeZone())
                        .format(Instant.now()));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(subsystem.name() + "-对接说明.html", StandardCharsets.UTF_8)
                        .build()
                        .toString());
        return "admin/subsystem-guide-export";
    }
}
