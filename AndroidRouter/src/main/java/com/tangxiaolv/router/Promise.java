
package com.tangxiaolv.router;

import android.os.Looper;

import com.tangxiaolv.router.exceptions.RouterException;

public class Promise {

    private final Asker asker;
    private Resolve resolve;
    private Reject reject;

    Promise(Asker asker) {
        this.asker = asker;
        asker.setPromise(this);
    }

    public void call() {
        call(null, null);
    }

    public void call(Resolve resolve) {
        call(resolve, null);
    }

    public void call(Reject reject) {
        call(null, reject);
    }

    public void call(Resolve resolve, Reject reject) {
        this.resolve = resolve;
        this.reject = reject;
        asker.request();
    }

    public void resolve(final Object o) {
        if (resolve == null)
            return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            resolve.call(o);
        } else {
            AndroidRouter.HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    resolve.call(o);
                }
            });
        }
    }

    public void reject(Exception e) {
        if (e == null)
            e = new RouterException("unkownException");
        RLog.e(e.getMessage());
        if (reject == null)
            return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            reject.call(e);
        } else {
            final Exception _e = e;
            AndroidRouter.HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    reject.call(_e);
                }
            });
        }
    }
}