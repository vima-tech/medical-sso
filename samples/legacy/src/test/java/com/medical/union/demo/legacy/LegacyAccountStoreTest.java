package com.medical.union.demo.legacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyAccountStoreTest {

    @Test
    void linksUnifiedIdentityAndKeepsOriginalSessionMechanism() {
        LegacyAccountStore accounts = new LegacyAccountStore();
        LegacyAccount account = accounts.byUsername("zs").orElseThrow();

        accounts.link(account, "P000123");
        String token = accounts.issueToken(account);

        assertThat(accounts.byPersonId("P000123")).contains(account);
        assertThat(accounts.byToken(token)).contains(account);

        accounts.revoke(token);
        assertThat(accounts.byToken(token)).isEmpty();
    }

    @Test
    void preventsOneUnifiedIdentityFromBindingTwoAccounts() {
        LegacyAccountStore accounts = new LegacyAccountStore();
        accounts.link(accounts.byUsername("zs").orElseThrow(), "P000123");

        assertThatThrownBy(() -> accounts.link(accounts.byUsername("ls").orElseThrow(), "P000123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已绑定");
    }
}
