package com.naqqa.elasticsearch.common.lifecycle;

public interface LifecycleComponent {

    Lifecycle.State lifecycleState();

    void addLifecycleListener(LifecycleListener listener);

    void removeLifecycleListener(LifecycleListener listener);

    void start();

    void stop();

    void close();
}
