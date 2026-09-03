package com.medical.union.portal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 登录没能完成时的提示页。
 *
 * <p>只有自动重来一次仍然失败、或本来就不可恢复的错误才会走到这里；
 * 偶发的 state 失效由 {@link LoginRetryFailureHandler} 直接重来，用户看不到这一页。
 */
@Controller
public class LoginFailedController {

    @GetMapping("/login-failed")
    String loginFailed(@RequestParam(required = false) String reason, Model model) {
        model.addAttribute("reason", reason == null || reason.isBlank()
                ? "登录未能完成，请重试" : reason);
        return "login-failed";
    }
}
