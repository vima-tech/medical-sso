package com.medical.union.demo;

import com.medical.union.sso.MedicalUser;
import com.medical.union.sso.MedicalUserMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class DemoController {

    private final MedicalUserMapper userMapper;

    public DemoController(MedicalUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/")
    String index(@AuthenticationPrincipal OidcUser principal, Model model) {
        model.addAttribute("medicalUser", userMapper.fromClaims(principal.getClaims()));
        return "index";
    }

    @GetMapping("/api/me")
    @ResponseBody
    MedicalUser me(@AuthenticationPrincipal OidcUser principal) {
        return userMapper.fromClaims(principal.getClaims());
    }
}

