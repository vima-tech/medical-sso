package com.medical.union.portal.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationDirectoryTest {

    @Test
    @DisplayName("中文系统名取首二字，医护一眼认得出")
    void chineseName() {
        assertThat(ApplicationDirectory.badge("检验信息系统")).isEqualTo("检验");
        assertThat(ApplicationDirectory.badge("住院医生工作站")).isEqualTo("住院");
    }

    @Test
    @DisplayName("遇到括号断开，不跨词拼字")
    void stopsAtPunctuation() {
        // 「检验信息系统（旧账号体系示例）」不应拼出跨括号的怪字
        assertThat(ApplicationDirectory.badge("检验信息系统（旧账号体系示例）")).isEqualTo("检验");
        assertThat(ApplicationDirectory.badge("门(诊)")).isEqualTo("门");
    }

    @Test
    @DisplayName("纯英文名取前两位字母")
    void latinName() {
        assertThat(ApplicationDirectory.badge("HIS")).isEqualTo("HI");
        assertThat(ApplicationDirectory.badge("PACS")).isEqualTo("PA");
    }

    @Test
    @DisplayName("中英混排时以中文为准：HIS 住院系统 显示「住院」比「HI」有用")
    void mixedPrefersChinese() {
        assertThat(ApplicationDirectory.badge("JDK 8 子系统接入示例")).isEqualTo("子系");
        assertThat(ApplicationDirectory.badge("HIS 住院系统")).isEqualTo("住院");
        assertThat(ApplicationDirectory.badge("Spring Boot 接入示例")).isEqualTo("接入");
    }

    @Test
    @DisplayName("名称里没有可用字符时不至于渲染成空白")
    void fallback() {
        assertThat(ApplicationDirectory.badge("---")).isEqualTo("应用");
    }
}
