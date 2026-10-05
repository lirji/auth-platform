package com.lrj.authz.governance.approval.infrastructure;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 出站固定目标阻止密钥被URL凭据、重定向及非TLS远程目标带走。 */
class HttpApprovalGatewayTest {
    @Test
    void nonLocalPlaintextAndCredentialUrlsAreRejected() {
        String key = "k".repeat(43);
        for (String url :
                new String[] {
                    "http://example.com",
                    "https://user:pass@example.com",
                    "https://example.com?x=1",
                    "https://example.com/path"
                })
            assertThatThrownBy(() -> new HttpApprovalGateway(url, key, "test"))
                    .hasMessage("INVALID_ARGUMENT");
        assertThat(new HttpApprovalGateway("http://127.0.0.1:1", key, "test").toString())
                .doesNotContain(key);
    }
}
