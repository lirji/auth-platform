package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import org.apache.ibatis.annotations.Param;

/** 回调与投递消费者共用同库Inbox，业务事件去重和传输nonce分开。 */
public interface ApprovalInboxMapper {
    /** 原事件体唯一插入，重复由锁后比对处理。 */
    int insert(@Param("p") Partition p,@Param("producer") String producer,@Param("event") String event,
               @Param("request") String request,@Param("hash") String hash,@Param("body") String body);
    /** 并发同事件由主键及行锁串行。 */
    Row lock(@Param("producer") String producer,@Param("event") String event);
    /** 冲突单独持久记录；返回409前仍提交此事务。 */
    int conflict(@Param("producer") String producer,@Param("event") String event,@Param("hash") String hash,@Param("original") String original);
    /** 只有尚未消费消息可推进终态，影响行数检查防静默覆盖。 */
    int finish(@Param("producer") String producer,@Param("event") String event,@Param("status") String status,@Param("result") String result);
    /** 原子登记已验证nonce，不把不同发送尝试混同业务事件。 */
    int nonce(@Param("producer") String producer,@Param("nonce") String nonce);
    /** 持久消息保持原字节对应的UTF8文本，不覆盖首次接收内容。 */
    record Row(String producer,String eventId,String requestId,String payloadHash,String payloadJson,String status,String result) {}
}
