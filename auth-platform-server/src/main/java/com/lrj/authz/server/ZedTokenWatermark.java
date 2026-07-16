package com.lrj.authz.server;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ZedToken 水位缓存：记录本实例最近一次写操作(write/delete)返回的 ZedToken。
 * 用途：调用方请求 {@code at_least_as_fresh} 却没带 token 时（此前会被 SpiceDB 拒绝），用水位代入——
 * 语义为「至少读到本 server 实例最近一次写之后的快照」。
 *
 * <p>边界（勿高估）：水位是<strong>单实例内存</strong>态——多实例部署时 A 实例的写不会推进 B 实例水位；
 * 跨实例/跨服务的严格写后读，调用方仍应回传自己写操作拿到的 ZedToken。ZedToken 本身不可比较大小，
 * 这里存"最近一次"而非"最大"，对单实例串行写足够（SpiceDB 快照单调向前）。
 */
@Component
public class ZedTokenWatermark {

    private final AtomicReference<String> latest = new AtomicReference<>();

    /** 写操作完成后推进水位（空 token 忽略）。 */
    public void advance(String zedToken) {
        if (zedToken != null && !zedToken.isBlank()) {
            latest.set(zedToken);
        }
    }

    /** 当前水位；本实例尚无写操作时为 null。 */
    public String latest() {
        return latest.get();
    }
}
