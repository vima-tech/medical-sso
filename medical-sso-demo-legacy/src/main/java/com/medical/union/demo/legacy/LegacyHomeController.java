package com.medical.union.demo.legacy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

/** 受保护的业务页面，用子系统自己的令牌判断登录态，与接入前完全一致。 */
@Controller
public class LegacyHomeController {

    private final LegacyAccountStore accounts;

    public LegacyHomeController(LegacyAccountStore accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/")
    String home(HttpServletRequest request, Model model) {
        Optional<LegacyAccount> account = LegacyLoginController.readToken(request)
                .flatMap(accounts::byToken);
        if (account.isEmpty()) {
            return "redirect:/login";
        }
        model.addAttribute("account", account.get());
        model.addAttribute("accounts", accounts.all());
        return "index";
    }
}
