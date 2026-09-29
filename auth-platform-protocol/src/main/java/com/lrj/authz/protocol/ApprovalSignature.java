package com.lrj.authz.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 双方签名相同原始字节；用途、路径、环境和发送时间都绑定，禁止JSON重序列化后验签。 */
public final class ApprovalSignature {
    public static final String HEADER="X-Approval-Signature";
    public static final int MAX_BODY=32768;
    private ApprovalSignature() {}

    /** 每次发送生成新nonce，稳定业务ID在body内；不得把旧业务时间当发送时间。 */
    public static String sign(String key,String producer,String environment,String path,byte[] body,Instant now) {
        validateKey(key);
        String metadata=producer+":"+environment+":"+now.getEpochSecond()+":"+UUID.randomUUID();
        return metadata+":"+digest(key,metadata,path,body);
    }

    /** 返回已验证nonce；接收方必须同库唯一登记，以拒绝窗口内的传输重放。 */
    public static String verify(String key,String producer,String environment,String path,byte[] body,String header,Instant now) {
        validateKey(key);
        try {
            if(header==null || header.length()>400 || body==null || body.length>MAX_BODY) throw invalid();
            String[] fields=header.split(":",-1);
            if(fields.length!=5 || !fields[0].equals(producer) || !fields[1].equals(environment)
                    || !fields[4].matches("[a-f0-9]{64}")) throw invalid();
            long sent=Long.parseLong(fields[2]), current=now.getEpochSecond();
            if(sent<current-120 || sent>current+120 || !UUID.fromString(fields[3]).toString().equals(fields[3])) throw invalid();
            String metadata=String.join(":",fields[0],fields[1],fields[2],fields[3]);
            if(!MessageDigest.isEqual(HexFormat.of().parseHex(fields[4]),HexFormat.of().parseHex(digest(key,metadata,path,body)))) throw invalid();
            return fields[3];
        } catch(IllegalArgumentException failure) { throw invalid(); }
    }

    /** 密钥必须来自私密配置，不允许短口令或隐式缺省。 */
    public static void validateKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{43,128}")) throw invalid();
    }
    private static String digest(String key,String metadata,String path,byte[] body) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
            mac.update((metadata+"\nPOST\n"+path+"\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch(java.security.GeneralSecurityException failure) { throw new IllegalStateException("HMAC不可用",failure); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("APPROVAL_SIGNATURE_INVALID"); }
}
