package com.lrj.authz.protocol;

/** 弃用元数据不代表撤权；Owner命令及历史回执与当前状态分别返回。 */
public final class CapabilityLifecycleDtos {
    private CapabilityLifecycleDtos() {}
    /** 协议使用固定code，数据库不持久化ordinal。 */
    public enum State {
        ACTIVE("ACTIVE"), DEPRECATED("DEPRECATED"), RETIRED("RETIRED");
        private final String code;
        State(String code){this.code=code;}
        /** 对外及持久化稳定值。 */
        public String code(){return code;}
    }
    /** 操作者从当前真实登录取得，调用方只能提交目标、版本和原因。 */
    public record Change(String applicationId,String capability,State state,Long expectedVersion,String reason,String commandId) {
        /** 最终退役有独立依据契约，不能经普通弃用／恢复输入绕过。 */
        public Change {if(state==State.RETIRED)throw new IllegalArgumentException("普通生命周期命令不能最终退役");}
    }
    /** 缺行的初始状态版本0，没有虚构的操作者或时间。 */
    public record Value(String applicationId,String capability,State state,long version,String reason,String changedBy,String updatedAt) {}
    /** 原命令结果固定保存，后续恢复不改变原弃用事实。 */
    public record Receipt(String commandId,String applicationId,String capability,State beforeState,State afterState,
            long beforeVersion,long afterVersion,String reason,String actor,String createdAt) {}
    /** 重放返回当前状态和原回执，不把原命令重新应用到未来状态。 */
    public record Mutation(Value current,Receipt receipt) {}
}
