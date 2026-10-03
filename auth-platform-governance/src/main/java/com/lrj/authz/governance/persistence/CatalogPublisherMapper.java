package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.authentication.MachineTokenAuthority;
import com.lrj.authz.governance.domain.CatalogPublisherModels.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 受限发布的目标和资格SQL，全部在XML中精确绑定应用／身份／实例。 */
public interface CatalogPublisherMapper {
    /** 只读当前实例目标，HTTP不能重绑定。 */
    Target target();
    /** 受控初始化仅尝试首次写，冲突后须核对原目标，不覆盖。 */
    int initializeTarget(@Param("target") Target target,@Param("operator") String operator,@Param("reason") String reason);
    /** 原Owner命令事件不可变，应用锁下读取后核对摘要。 */
    Event event(@Param("app") String app,@Param("command") String command);
    /** 当前Owner的有界完整清单，不返回密钥或其他应用数据。 */
    List<Row> list(@Param("app") String app,@Param("limit") int limit);
    /** 只在当前Owner或已核验机器的精确应用内查委派。 */
    Row delegation(@Param("app") String app,@Param("id") String id);
    /** slot不能重用；轮换需要新客户端和新委派。 */
    Row publisher(@Param("app") String app,@Param("publisher") String publisher);
    /** 所有资格与主库当前时间一起成立，锁保持到发布／回执事务结束。 */
    Row authorize(@Param("slot") MachineTokenAuthority slot,@Param("expiresAt") String expiresAt);
    /** 失败区分Token到期和发布委派失效，不使用事务开始时的时间。 */
    boolean tokenAlive(@Param("expiresAt") String expiresAt);
    /** PG当前时点验证期限，不能由JVM默认时区或旧事务时间放宽。 */
    boolean validPeriod(@Param("until") String until);
    /** 建立委派与事件必须使用同一事务。 */
    int create(@Param("id") String id,@Param("slot") MachineTokenAuthority slot,@Param("owner") String owner,
               @Param("until") String until,@Param("command") String command,@Param("reason") String reason);
    /** 单向CAS，影响行数必须为1。 */
    int disable(@Param("app") String app,@Param("id") String id,@Param("expected") long expected);
    /** 不可变原命令结果，不提供覆盖事件入口。 */
    int eventInsert(@Param("app") String app,@Param("command") String command,@Param("delegation") String delegation,
                    @Param("owner") String owner,@Param("operation") String operation,@Param("hash") String hash,
                    @Param("previous") long previous,@Param("resulting") long resulting,@Param("reason") String reason,@Param("result") String result);
    /** 机器来源关系与MG05固定票据同事务。 */
    int previewInsert(@Param("preview") String preview,@Param("row") Row row);
    /** 不消费HUMAN或其他委派的票据。 */
    boolean ownPreview(@Param("preview") String preview,@Param("app") String app,@Param("delegation") String delegation);
    /** 与固定发布回执同事务记录真实SERVICE，不伪装HUMAN Owner。 */
    int releaseInsert(@Param("app") String app,@Param("version") long version,@Param("row") Row row);
    /** 原命令只能关联当前有效委派自己的已提交发布。 */
    Long receiptVersion(@Param("app") String app,@Param("command") String command,@Param("delegation") String delegation);
    /** 固定原actor来自数据库，不根据当前slot或Owner重造历史。 */
    Actor actor(@Param("app") String app,@Param("version") long version);
    /** DB内部身份字段与公开回执隔开；unexpired是当前PG时间事实。 */
    record Row(String id,String publisherId,String applicationId,String targetInstanceId,String environment,String servicePrincipal,
               String ownerPrincipal,String issuer,String subject,String clientId,String status,long version,String validUntil,
               String commandId,String reason,String createdAt,String disabledAt,boolean unexpired) {}
    /** 原命令快照不会被后续禁用改变，资格依然查询当前行。 */
    record Event(String operatorPrincipal,String payloadHash,String resultJson) {}
}
