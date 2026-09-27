package com.naqqa.elasticsearch.common.lifecycle;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class LifecycleTest {

    private static final class Counting extends AbstractLifecycleComponent {
        int starts;
        int stops;
        int closes;

        @Override
        protected void doStart() {
            starts++;
        }

        @Override
        protected void doStop() {
            stops++;
        }

        @Override
        protected void doClose() {
            closes++;
        }
    }

    @Test
    public void testLifecycleTransitions() {
        Counting c = new Counting();
        Assert.assertEquals(Lifecycle.State.INITIALIZED, c.lifecycleState());
        c.start();
        Assert.assertEquals(1, c.starts);
        Assert.assertEquals(Lifecycle.State.STARTED, c.lifecycleState());
        c.start();
        Assert.assertEquals(1, c.starts);
        c.stop();
        Assert.assertEquals(1, c.stops);
        c.close();
        Assert.assertEquals(1, c.closes);
        Assert.assertEquals(Lifecycle.State.CLOSED, c.lifecycleState());
    }

    @Test
    public void testCloseFromStartedStopsFirst() {
        Counting c = new Counting();
        c.start();
        c.close();
        Assert.assertEquals(1, c.stops);
        Assert.assertEquals(1, c.closes);
    }
}
