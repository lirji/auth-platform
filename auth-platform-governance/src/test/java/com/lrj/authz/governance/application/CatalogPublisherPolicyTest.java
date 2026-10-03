package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogPublisherModels.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 自动资格按完整业务入口判定，不能靠差异数量或现有Grant推断风险。 */
class CatalogPublisherPolicyTest {
    private final List<Capability> caps=List.of(new Capability("shop.read","store",Risk.NORMAL),new Capability("shop.write","store",Risk.HIGH));
    private Manifest current() {return manifest(caps,List.of(new Menu("stores",null,"/stores",List.of("shop.read"),"门店",0)));}
    private Manifest manifest(List<Capability> capabilities,List<Menu> menus) {return new Manifest("1","shop",2,capabilities,menus);}
    @Test void labelsOrderingAndPureGroupingMayChangeWithoutPermissionChanges() {
        var m=manifest(List.of(caps.get(1),caps.get(0)),List.of(new Menu("group",null,null,List.of(),"经营",0),new Menu("stores","group","/stores",List.of("shop.read"),"门店目录",1)));
        assertThat(CatalogPublisherPolicy.classify(current(),m)).isEqualTo(Eligibility.AUTOMATION_ALLOWED);
        assertThat(CatalogPublisherPolicy.classify(m,current())).isEqualTo(Eligibility.AUTOMATION_ALLOWED);
    }
    @Test void firstDirectoryNewUnboundCapabilityAndChangedCapabilityRequireHuman() {
        assertThat(CatalogPublisherPolicy.classify(null,current())).isEqualTo(Eligibility.REQUIRES_OWNER_REVIEW);
        assertThat(CatalogPublisherPolicy.classify(current(),manifest(List.of(caps.get(0),caps.get(1),new Capability("shop.export","store",Risk.NORMAL)),current().menus()))).isEqualTo(Eligibility.REQUIRES_OWNER_REVIEW);
        assertThat(CatalogPublisherPolicy.classify(current(),manifest(List.of(new Capability("shop.read","order",Risk.NORMAL),caps.get(1)),current().menus()))).isEqualTo(Eligibility.REQUIRES_OWNER_REVIEW);
    }
    @ParameterizedTest @ValueSource(strings={"route","binding","rename","remove","add","permissionGroup"})
    void allBusinessEntryChangesRequireHuman(String change) {
        List<Menu> menus=switch(change) {
            case "route" -> List.of(new Menu("stores",null,"/new-stores",List.of("shop.read")));
            case "binding" -> List.of(new Menu("stores",null,"/stores",List.of("shop.write")));
            case "rename" -> List.of(new Menu("different",null,"/stores",List.of("shop.read")));
            case "remove" -> List.of();
            case "add" -> List.of(current().menus().getFirst(),new Menu("export",null,"/export",List.of("shop.read")));
            default -> List.of(current().menus().getFirst(),new Menu("restricted",null,null,List.of("shop.read")));
        };
        assertThat(CatalogPublisherPolicy.classify(current(),manifest(caps,menus))).isEqualTo(Eligibility.REQUIRES_OWNER_REVIEW);
    }
    @Test void invalidTreeIsRejectedInsteadOfClassifiedAsSafe() {
        assertThatThrownBy(()->CatalogPublisherPolicy.classify(current(),manifest(caps,List.of(new Menu("stores","missing","/stores",List.of("shop.read")))))).hasMessage("INVALID_ARGUMENT");
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"target_instance_id\":\"x\",\"target_instance_id\":\"y\"}",
        "{\"operator\":\"owner\"}","{\"command_id\":3}","{} {}","null",
        "{\"expected_version\":1.5}","{\"expected_version\":\"1\"}","{\"expected_version\":true}"
    })
    void smallRequestsRejectAmbiguousOrForgedFields(String json) {
        Class<?> type=json.contains("expected_version")?Disable.class:Publish.class;
        assertThatThrownBy(()->CatalogPublisherJson.small(input(json),type)).hasMessage("INVALID_ARGUMENT");
    }
    @Test void smallBudgetAndPreviewForbiddenFieldsAreStrict() {
        assertThatThrownBy(()->CatalogPublisherJson.small(input(" ".repeat(4097)),Publish.class)).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(()->CatalogPublication.read(input("{\"decision\":\"KEEP_CURRENT_GRANTS\"}"),PreviewInput.class)).hasMessage("INVALID_ARGUMENT");
        var value=CatalogPublisherJson.small(input("{\"target_instance_id\":\"fixed\",\"environment\":\"test\",\"command_id\":\"original\",\"preview_id\":\"ticket\"}"),Publish.class);
        assertThat(value.commandId()).isEqualTo("original");
    }
    private ByteArrayInputStream input(String value) {return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
}
