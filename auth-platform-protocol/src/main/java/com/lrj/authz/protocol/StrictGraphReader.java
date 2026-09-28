package com.lrj.authz.protocol;

import java.util.List;
import java.util.Map;

/** 治理Grant严格批量读取，不把缺项、条件结果或协议错误当普通DENY。 */
public interface StrictGraphReader {
    /** 结果必须完整对应输入Grant；水位只按atLeastAsFresh传输，不比较字符串大小。 */
    Map<String,Boolean> checkEligible(String membershipId, long generation, List<String> grantIds, String zedToken);
}
