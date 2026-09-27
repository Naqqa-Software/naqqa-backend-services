package com.naqqa.elasticsearch.monitor.hotthreads;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HotThreadsSampler {

    private final ThreadMXBean threadMXBean;

    public HotThreadsSampler() {
        this(ManagementFactory.getThreadMXBean());
    }

    public HotThreadsSampler(ThreadMXBean threadMXBean) {
        this.threadMXBean = threadMXBean;
        if (threadMXBean.isThreadCpuTimeSupported() && !threadMXBean.isThreadCpuTimeEnabled()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        }
    }

    public List<HotThread> sample(int samples, long intervalMillis, int stackDepth) throws InterruptedException {
        int effectiveSamples = Math.max(1, samples);
        long sleepEach = Math.max(1, intervalMillis / effectiveSamples);
        long[] ids = threadMXBean.getAllThreadIds();
        Map<Long, Long> startCpu = new HashMap<>();
        for (long id : ids) {
            long cpu = threadMXBean.getThreadCpuTime(id);
            startCpu.put(id, cpu);
        }
        long elapsedNanos = 0;
        for (int i = 0; i < effectiveSamples; i++) {
            long before = System.nanoTime();
            Thread.sleep(sleepEach);
            elapsedNanos += System.nanoTime() - before;
        }
        ThreadInfo[] infos = threadMXBean.getThreadInfo(ids, stackDepth);
        List<HotThread> result = new ArrayList<>();
        long selfId = Thread.currentThread().threadId();
        for (int i = 0; i < ids.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null || ids[i] == selfId) {
                continue;
            }
            long endCpu = threadMXBean.getThreadCpuTime(ids[i]);
            long startCpuTime = startCpu.getOrDefault(ids[i], 0L);
            if (endCpu < 0 || startCpuTime < 0) {
                continue;
            }
            long deltaNanos = Math.max(0, endCpu - startCpuTime);
            double percent = elapsedNanos > 0 ? (deltaNanos * 100.0) / elapsedNanos : 0.0;
            List<String> stack = new ArrayList<>();
            for (StackTraceElement element : info.getStackTrace()) {
                stack.add(element.toString());
            }
            result.add(new HotThread(info.getThreadId(), info.getThreadName(), info.getThreadState().name(),
                    percent, deltaNanos / 1_000_000.0, stack));
        }
        result.sort(Comparator.comparingDouble(HotThread::percentBusy).reversed());
        return result;
    }

    public String render(List<HotThread> threads, int topN, long intervalMillis, int samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("::: Hot threads at interval=").append(intervalMillis).append("ms, samples=").append(samples)
                .append(", busiestThreads=").append(topN).append(", ignoreIdleThreads=true\n\n");
        int count = 0;
        for (HotThread thread : threads) {
            if (count >= topN) {
                break;
            }
            if (thread.percentBusy() <= 0.0) {
                continue;
            }
            sb.append(String.format("%.1f%% (%.1fms out of %dms) cpu usage by thread '%s'%n", thread.percentBusy(),
                    thread.cpuTimeMillis(), intervalMillis, thread.threadName()));
            sb.append("  ").append(thread.state()).append('\n');
            for (String frame : thread.stackTrace()) {
                sb.append("    at ").append(frame).append('\n');
            }
            sb.append('\n');
            count++;
        }
        return sb.toString();
    }

    public record HotThread(long threadId, String threadName, String state, double percentBusy,
            double cpuTimeMillis, List<String> stackTrace) {
    }
}
