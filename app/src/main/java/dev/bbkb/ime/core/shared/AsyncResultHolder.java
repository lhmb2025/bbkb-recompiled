package dev.bbkb.ime.core.shared;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;



public class AsyncResultHolder<E> {

    private E result;

    private final Object lock = new Object();

    private final CountDownLatch latch = new CountDownLatch(1);

    public void set(E e) {
        synchronized (this.lock) {
            if (this.latch.getCount() > 0) {
                this.result = e;
                this.latch.countDown();
            }
        }
    }

    public E get(E e, long timeoutMillis) {
        try {
            return this.latch.await(timeoutMillis, TimeUnit.MILLISECONDS) ? this.result : e;
        } catch (InterruptedException unused) {
            return e;
        }
    }
}
