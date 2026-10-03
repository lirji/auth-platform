package com.lrj.authz.governance.web;

import com.lrj.authz.protocol.RoleMigrationDtos.PreviewRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 迁移预览复用严格JSON边界，不允许通过读请求植入操作者或写入意图。 */
class RoleMigrationWebTest {
    private PreviewRequest read(String value){return AccessWeb.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),PreviewRequest.class);}
    @Test void exactInputPreservesExplicitIds(){var r=read("{\"tenant_id\":\"t\",\"application_id\":\"app\",\"environment\":\"test\",\"old_role_id\":\"old\",\"new_role_id\":\"new\",\"grant_ids\":[\"one\"]}");assertThat(r.grantIds()).containsExactly("one");}
    @Test void unknownActorDuplicateKeysAndTrailingBodyAreRejected(){for(String body:new String[]{"{\"operator\":\"victim\"}","{\"grant_ids\":[],\"grant_ids\":[]}","{} {}"})assertThatThrownBy(()->read(body)).hasMessage("INVALID_ARGUMENT");}
    @Test void oversizedAndScalarRootAreRejected(){assertThatThrownBy(()->read("{\"environment\":\""+"x".repeat(17000)+"\"}")).hasMessage("INVALID_ARGUMENT");assertThatThrownBy(()->read("[]")).hasMessage("INVALID_ARGUMENT");}
}
