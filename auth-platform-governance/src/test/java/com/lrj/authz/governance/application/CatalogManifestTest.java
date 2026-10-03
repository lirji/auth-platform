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
                valid.replace("\"schema_version\":\"1\"","\"schema_version\":1"),valid.replace("\"NORMAL\"","0"),
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
    @Test void displaySnapshotPreservesLegacyAuthorizationJsonAndRoundTripsChinese() {
        var old=manifest(List.of(new Menu("stores",null,"/stores",List.of("trade.read"))));
        var named=manifest(List.of(new Menu("stores",null,"/stores",List.of("trade.read"),"商家与门店",0)));
        assertThat(CatalogManifest.json(named)).isEqualTo(CatalogManifest.json(old)).doesNotContain("label","position");
        assertThat(CatalogManifest.hash(named)).isEqualTo(CatalogManifest.hash(old));
        assertThat(CatalogManifest.withPresentation(CatalogManifest.read(CatalogManifest.json(named)),CatalogManifest.presentationJson(named),CatalogManifest.presentationHash(named))).isEqualTo(named);
        assertThatThrownBy(()->CatalogManifest.withPresentation(old,"[{\"code\":\"stores\",\"label\":\"损坏\",\"position\":0}]",CatalogManifest.presentationHash(named))).isInstanceOf(GovernanceException.class);
        assertThatThrownBy(()->CatalogManifest.withPresentation(old,"[{\"code\":\"stores\",\"label\":\"商家与门店\"}]",CatalogManifest.presentationHash(named))).isInstanceOf(GovernanceException.class);
    }
    @Test void invalidDisplayPairsAndDuplicatePositionsAreRejected() {
        for (var menu:List.of(new Menu("stores",null,"/stores",List.of("trade.read"),"门店",null),new Menu("stores",null,"/stores",List.of("trade.read"),null,0),new Menu("stores",null,"/stores",List.of("trade.read"),"<script>",0),new Menu("stores",null,"/stores",List.of("trade.read")," 门店",0),new Menu("stores",null,"/stores",List.of("trade.read"),"门店",100)))
            assertThatThrownBy(()->CatalogManifest.normalize(manifest(List.of(menu)))).isInstanceOf(GovernanceException.class);
        assertThatThrownBy(()->CatalogManifest.normalize(manifest(List.of(new Menu("a",null,null,List.of(),"目录",0),new Menu("b","a","/stores",List.of("trade.read"),"门店",0))))).isInstanceOf(GovernanceException.class);
    }

}
