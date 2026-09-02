package com.medical.union.portal.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 子系统登记表单。只收四项，其余全部推导。
 */
public class SubsystemForm {

    @NotBlank(message = "请填写系统名称")
    @Size(max = 64, message = "系统名称不超过 64 个字符")
    private String name;

    @NotBlank(message = "请填写系统编码")
    @Pattern(regexp = "[a-z][a-z0-9-]{1,48}[a-z0-9]",
            message = "系统编码用小写字母、数字和中划线，以字母开头，例如 his-web")
    private String code;

    @NotBlank(message = "请填写系统访问地址")
    @Pattern(regexp = "https?://[^\\s/][^\\s]*",
            message = "系统访问地址需以 http:// 或 https:// 开头，例如 https://his.intra.example")
    private String baseUrl;

    /** boot3 或 boot2，决定生成哪一套对接代码。 */
    @NotBlank(message = "请选择子系统技术栈")
    private String stack = Stack.BOOT2;

    public static final class Stack {
        public static final String BOOT3 = "boot3";
        public static final String BOOT2 = "boot2";

        private Stack() {
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(String stack) {
        this.stack = stack;
    }

    /** 去掉结尾斜杠，后续所有地址都基于它拼接。 */
    public String normalizedBaseUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
