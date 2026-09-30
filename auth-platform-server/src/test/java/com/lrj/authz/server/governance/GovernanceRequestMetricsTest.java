package com.lrj.authz.server.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

/** 指标必须保持拒绝与系统失败的区别，且攻击者路径不能造成标签膨胀。 */
class GovernanceRequestMetricsTest {
    @Test void denyErrorAuthenticationAndAllowRemainDistinct() throws Exception {
        var registry=new SimpleMeterRegistry();var filter=new GovernanceRequestMetrics(registry);
        int[] statuses={200,403,503,401};String[] outcomes={"ALLOW","DENY","ERROR","AUTHN"};
        for(int i=0;i<statuses.length;i++) {
            var request=new MockHttpServletRequest("POST","/internal/governance/v1/access/check-resource");
            var response=new MockHttpServletResponse();int status=statuses[i];
            filter.doFilter(request,response,(req,res)->{req.setAttribute(GovernanceRequestMetrics.OUTCOME,"ALLOW");response.setStatus(status);});
            assertThat(registry.get("authz.governance.requests").tags("operation","RESOURCE","outcome",outcomes[i]).timer().count()).isEqualTo(1);
        }
    }
    @Test void bulkMixedAndUnknownPathsHaveBoundedLabels() throws Exception {
        var json=new ObjectMapper();
        assertThat(GovernanceRequestMetrics.OutcomeAdvice.decision(json.readTree("{\"results\":[{\"decision\":\"ALLOW\"},{\"decision\":\"DENY\"}]}"))).isEqualTo("MIXED");
        assertThat(GovernanceRequestMetrics.OutcomeAdvice.decision(json.readTree("{\"decision\":\"attacker-label\"}"))).isNull();
        assertThat(GovernanceRequestMetrics.OutcomeAdvice.decision(json.readTree("{\"results\":[{\"decision\":\"ALLOW\"},{\"decision\":\"DENY\"},{}]}"))).isEqualTo("ERROR");
        assertThat(GovernanceRequestMetrics.operation("/internal/governance/v1/tenant-secret/random")).isEqualTo("OTHER");
    }
    @Test void escapedFailureIsErrorAndNeverLeaksExceptionText() {
        var registry=new SimpleMeterRegistry();var filter=new GovernanceRequestMetrics(registry);
        assertThatThrownBy(()->filter.doFilter(new MockHttpServletRequest("POST","/internal/governance/v1/access/check"),new MockHttpServletResponse(),
            (req,res)->{throw new IllegalStateException("private-token");})).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("authz.governance.requests").tags("operation","CHECK","outcome","ERROR").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters().toString()).doesNotContain("private-token");
    }
}
