package com.lrj.authz.governance.web;
import com.lrj.authz.governance.application.GovernanceException;
import com.lrj.authz.protocol.AccessDtos.CreateRole;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/** HTTP结构的类型/主体/时间错误不能进入管理用例。 */
class AccessWebTest {
 @Test void rejectsUnknownDuplicateNullAndCoercedInput(){
  for(String input:new String[]{"null","{}{}","{\"principal_id\":\"forged\"}","{\"role_version\":\"1\"}","{\"role_version\":1,\"role_version\":2}","{\"role_version\":1.1}"," ".repeat(16385)})
   assertThatThrownBy(()->AccessWeb.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),CreateRole.class)).isInstanceOf(GovernanceException.class);
 }
 @Test void requiresUtcTime(){assertThat(AccessWeb.instant("2026-01-01T00:00:00Z")).isNotNull();
  for(String s:new String[]{null,"2026-01-01","2026-01-01T00:00:00+08:00"})assertThatThrownBy(()->AccessWeb.instant(s)).isInstanceOf(GovernanceException.class);
 }
}
