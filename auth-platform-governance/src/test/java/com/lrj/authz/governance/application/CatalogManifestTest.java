package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.CatalogModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 不可信清单不能通过宽松JSON、命名空间或菜单引用扩大权限。 */
class CatalogManifestTest {
    private Manifest manifest(List<Menu> menus) { return new Manifest("1","trade",1,List.of(new Capability("trade.read","store",Risk.NORMAL)),menus); }
    @Test void rejectsUnknownDuplicateAndCoercedFields() {
        String valid=CatalogManifest.json(manifest(List.of()));
        for(String value:List.of(valid.replace("\"manifest_version\":1","\"manifest_version\":\"1\""),
                valid.replace("\"manifest_version\":1","\"manifest_version\":1.2"),
                valid.replace("{","{\"extra\":true,"),valid.replace("\"schema_version\":\"1\"","\"schema_version\":\"1\",\"schema_version\":\"1\""),valid+"{}")) {
            assertThatThrownBy(()->CatalogManifest.read(value)).isInstanceOf(GovernanceException.class);
        }
    }
    @Test void rejectsForeignCapabilityAndInvalidMenuGraph() {
        assertThatThrownBy(()->CatalogManifest.normalize(new Manifest("1","other",1,manifest(List.of()).capabilities(),List.of()))).isInstanceOf(GovernanceException.class);
        for(List<Menu> menus:List.of(List.of(new Menu("a","b",null,List.of()),new Menu("b","a",null,List.of())),
                List.of(new Menu("a","missing",null,List.of())),List.of(new Menu("a",null,"//evil",List.of("trade.read"))),
                List.of(new Menu("a",null,"/stores",List.of("trade.refund"))),List.of(new Menu("a",null,"/stores",List.of())))) {
            assertThatThrownBy(()->CatalogManifest.normalize(manifest(menus))).isInstanceOf(GovernanceException.class);
        }
    }
    @Test void canonicalDigestAndOriginRules() {
        Manifest a=new Manifest("1","trade",1,List.of(new Capability("trade.write","store",Risk.HIGH),new Capability("trade.read","store",Risk.NORMAL)),List.of());
        Manifest b=new Manifest("1","trade",1,a.capabilities().reversed(),List.of());
        assertThat(CatalogManifest.hash(a)).isEqualTo(CatalogManifest.hash(b));
        CatalogManifest.origin("https://shop.example");CatalogManifest.origin("http://127.0.0.1:8601");
        for(String origin:List.of("http://shop.example","https://user@shop.example","https://shop.example/path","javascript:evil")) {
            assertThatThrownBy(()->CatalogManifest.origin(origin)).isInstanceOf(GovernanceException.class);
        }
    }
}
