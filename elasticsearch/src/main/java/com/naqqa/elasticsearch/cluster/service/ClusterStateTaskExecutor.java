package com.naqqa.elasticsearch.cluster.service;

import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.util.List;

public interface ClusterStateTaskExecutor<T> {

    ClusterState execute(ClusterState currentState, List<T> tasks) throws Exception;

    interface TaskListener<T> {
        void onSuccess(T task, ClusterState newState);

        void onFailure(T task, Exception e);
    }
}
