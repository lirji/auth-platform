package com.lrj.authz.sdk.internal.http;

import com.lrj.authz.sdk.CentralAccessException;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 内部HTTP响应预算适配；只承载字节收集与取消，判权协议校验仍归公开客户端。 */
public final class BoundedResponseBodies {
    private BoundedResponseBodies() {}

    /** 每次交换创建独立订阅者，超限取消当前订阅而不污染后续判权请求。 */
    public static HttpResponse.BodyHandler<byte[]> handler(int maximum) {
        return ignored -> new BoundedBody(maximum);
    }

    /** 限制响应字节数，恶意/损坏服务不能用无限body占用内存；HttpClient请求超时覆盖订阅完成。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private final int maximum;

        BoundedBody(int maximum) {
            this.maximum = maximum;
        }

        /** 返回本交换的有界收集结果，调用方继续等待完整body而非只有响应头。 */
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        /** 绑定当前交换的订阅，预算越界只取消这次响应，避免影响独立请求。 */
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        /** 逐批核对原响应字节预算，超限时取消收集并保留既有故障语义。 */
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > maximum) {
                    subscription.cancel();
                    delegate.onError(new CentralAccessException(503));
                    return;
                }
            }
            delegate.onNext(items);
        }

        /** 将响应订阅失败传递给等待方，不能用空body掩盖传输故障。 */
        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        /** 仅在原响应订阅结束后完成收集，避免返回未完整接收的协议内容。 */
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
