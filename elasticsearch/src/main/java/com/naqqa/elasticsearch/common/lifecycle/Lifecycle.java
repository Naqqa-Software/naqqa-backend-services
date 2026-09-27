package com.naqqa.elasticsearch.common.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

public final class Lifecycle {

    public enum State {
        INITIALIZED,
        STOPPED,
        STARTED,
        CLOSED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZED);

    public State state() {
        return state.get();
    }

    public boolean initialized() {
        return state.get() == State.INITIALIZED;
    }

    public boolean started() {
        return state.get() == State.STARTED;
    }

    public boolean stopped() {
        return state.get() == State.STOPPED;
    }

    public boolean closed() {
        return state.get() == State.CLOSED;
    }

    public boolean canMoveToStarted() {
        State s = state.get();
        if (s == State.CLOSED) {
            throw new IllegalStateException("Can't move to started state when closed");
        }
        return s == State.INITIALIZED || s == State.STOPPED;
    }

    public boolean moveToStarted() {
        State s = state.get();
        if (s == State.STARTED) {
            return false;
        }
        if (!canMoveToStarted()) {
            throw new IllegalStateException("Can't move to started with current state [" + s + "]");
        }
        return state.compareAndSet(s, State.STARTED);
    }

    public boolean canMoveToStopped() {
        State s = state.get();
        if (s == State.CLOSED) {
            throw new IllegalStateException("Can't move to stopped state when closed");
        }
        return s == State.STARTED;
    }

    public boolean moveToStopped() {
        State s = state.get();
        if (!canMoveToStopped()) {
            if (s == State.STOPPED) {
                return false;
            }
            throw new IllegalStateException("Can't move to stopped with current state [" + s + "]");
        }
        return state.compareAndSet(s, State.STOPPED);
    }

    public boolean canMoveToClosed() {
        return state.get() != State.CLOSED;
    }

    public boolean moveToClosed() {
        State s = state.get();
        if (s == State.CLOSED) {
            return false;
        }
        return state.compareAndSet(s, State.CLOSED);
    }
}
