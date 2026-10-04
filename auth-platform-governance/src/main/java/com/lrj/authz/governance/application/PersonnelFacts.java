package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lrj.authz.governance.domain.DirectoryModels.Entry;
import com.lrj.authz.governance.domain.IdentityModels.Membership;
import com.lrj.authz.protocol.PersonnelImpactDtos.*;
import java.io.IOException;
import java.util.List;

/** 前后证据只取状态、直接任职和代际，避免复制当前目录中的登录绑定。 */
public final class PersonnelFacts {
    private static final ObjectMapper JSON=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private PersonnelFacts() {}
    /** 必要目录字段保持源端排序和日期语义，不推导岗位权限。 */
    public static Facts facts(Entry e) {
        var p=DirectoryJson.readPayload(e.payloadJson());
        return p.employee()!=null ? new Facts(p.employee().status(),p.employee().assignments(),null) : new Facts(p.organization().status(),List.of(),p.organization().parentId());
    }
    /** 首次目录内容没有前值，不从当前事实反向生成旧历史。 */
    public static String snapshot(Entry e,Membership m) {
        if(e==null)return null;
        try{return JSON.writeValueAsString(new Snapshot(facts(e),m==null?null:new MemberState(m.status().code(),m.generation(),m.version())));}
        catch(IOException corrupt){throw unavailable();}
    }
    /** 只解析本片保存的最小事实，损坏不返回空历史。 */
    public static Snapshot readSnapshot(String json) {
        if(json==null)return null;
        try{return JSON.readValue(json,Snapshot.class);}
        catch(IOException corrupt){throw unavailable();}
    }
    private static GovernanceException unavailable(){return new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);}
}
