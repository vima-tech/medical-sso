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

    /** boot3、boot2、bridge 或 gateway，决定生成哪一套对接说明。 */
    @NotBlank(message = "请选择接入方式")
    private String stack = Stack.BOOT2;

    public static final class Stack {
        public static final String BOOT3 = "boot3";
        public static final String BOOT2 = "boot2";
        /** 已有账号体系：保留原登录，用桥接模式接上统一身份，两种登录并存。 */
        public static final String BRIDGE = "bridge";
        /** 改不动的系统：登录在接入网关上完成，业务系统一行代码都不用改。 */
        public static final String GATEWAY = "gateway";

        /**
         * 列表和详情页显示的接入方式名称。
         *
         * <p>按「接入形态」命名而不是按运行时命名：boot2/boot3 是同一种标准接入，
         * 只是 JDK 不同；把它们并列成「JDK 8 / 桥接模式」会让四个选项不在一个维度上。
         */
        public static String label(String stack) {
            if (BOOT3.equals(stack)) {
                return "标准接入 · JDK 17";
            }
            if (BOOT2.equals(stack)) {
                return "标准接入 · JDK 8";
            }
            if (BRIDGE.equals(stack)) {
                return "桥接模式";
            }
            if (GATEWAY.equals(stack)) {
                return "接入网关";
            }
            return stack == null ? "-" : stack;
        }

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
