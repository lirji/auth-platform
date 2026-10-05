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

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

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

        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }
}
