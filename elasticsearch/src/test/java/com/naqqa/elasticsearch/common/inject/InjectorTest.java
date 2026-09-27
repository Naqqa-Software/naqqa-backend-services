package com.naqqa.elasticsearch.common.inject;

import com.naqqa.elasticsearch.common.lifecycle.AbstractLifecycleComponent;
import com.naqqa.elasticsearch.common.lifecycle.Lifecycle;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class InjectorTest {

    static final class ServiceA extends AbstractLifecycleComponent {
        @Override
        protected void doStart() {
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected void doClose() {
        }
    }

    static final class ServiceB extends AbstractLifecycleComponent {
        final ServiceA a;

        ServiceB(ServiceA a) {
            this.a = a;
        }

        @Override
        protected void doStart() {
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected void doClose() {
        }
    }

    @Test
    public void testConstructorInjectionAndSingleton() {
        Injector injector = new Injector();
        ServiceB b = injector.getInstance(ServiceB.class);
        Assert.assertNotNull(b.a);
        ServiceA a1 = injector.getInstance(ServiceA.class);
        Assert.assertSame(b.a, a1);
    }

    @Test
    public void testStartAllAndCloseAll() {
        Injector injector = new Injector();
        ServiceB b = injector.getInstance(ServiceB.class);
        injector.startAll();
        Assert.assertEquals(Lifecycle.State.STARTED, b.lifecycleState());
        Assert.assertEquals(Lifecycle.State.STARTED, b.a.lifecycleState());
        injector.closeAll();
        Assert.assertEquals(Lifecycle.State.CLOSED, b.lifecycleState());
    }
}
