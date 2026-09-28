package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.store.Directory;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class IndexFileDeleter {

    private final Directory directory;
    private final Map<String, Integer> refCounts = new HashMap<>();
    private final List<CommitPoint> retainedCommits = new ArrayList<>();

    IndexFileDeleter(Directory directory) {
        this.directory = directory;
    }

    private static final class CommitPoint {
        private final String commitFileName;
        private final Set<String> files;
        private int holdCount;

        CommitPoint(String commitFileName, Set<String> files) {
            this.commitFileName = commitFileName;
            this.files = files;
        }

        Set<String> allFiles() {
            Set<String> all = new HashSet<>(files);
            if (commitFileName != null) {
                all.add(commitFileName);
            }
            return all;
        }
    }

    private static boolean isIndexDataFile(String name) {
        return name.startsWith("_") || name.startsWith(SegmentInfos.COMMIT_PREFIX) || name.startsWith(SegmentInfos.PENDING_PREFIX);
    }

    synchronized void seedInitialCommit(String commitFileName, Set<String> files, Set<String> filesOnDisk) {
        CommitPoint cp = new CommitPoint(commitFileName, files);
        retainedCommits.add(cp);
        incRefLocked(cp.allFiles());
        for (String f : filesOnDisk) {
            if (isIndexDataFile(f) && refCounts.getOrDefault(f, 0) <= 0) {
                deleteQuietly(f);
            }
        }
    }

    synchronized void incRefReader(Set<String> files) {
        incRefLocked(files);
    }

    synchronized void decRefReader(Set<String> files) {
        decRefLocked(files);
    }

    synchronized void onNewCommit(String commitFileName, Set<String> files) {
        CommitPoint cp = new CommitPoint(commitFileName, files);
        incRefLocked(cp.allFiles());
        retainedCommits.add(cp);
        for (int i = retainedCommits.size() - 2; i >= 0; i--) {
            CommitPoint old = retainedCommits.get(i);
            if (old.holdCount == 0) {
                retainedCommits.remove(i);
                decRefLocked(old.allFiles());
            }
        }
    }

    synchronized Releasable holdLastCommit() {
        if (retainedCommits.isEmpty()) {
            return () -> {
            };
        }
        CommitPoint cp = retainedCommits.get(retainedCommits.size() - 1);
        cp.holdCount++;
        AtomicBoolean released = new AtomicBoolean(false);
        return () -> releaseHold(cp, released);
    }

    private synchronized void releaseHold(CommitPoint cp, AtomicBoolean released) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        cp.holdCount--;
        if (cp.holdCount == 0 && retainedCommits.indexOf(cp) >= 0 && retainedCommits.indexOf(cp) < retainedCommits.size() - 1) {
            retainedCommits.remove(cp);
            decRefLocked(cp.allFiles());
        }
    }

    private void incRefLocked(Set<String> files) {
        for (String f : files) {
            refCounts.merge(f, 1, Integer::sum);
        }
    }

    private void decRefLocked(Set<String> files) {
        for (String f : files) {
            Integer c = refCounts.get(f);
            int nc = (c == null ? 0 : c) - 1;
            if (nc <= 0) {
                refCounts.remove(f);
                deleteQuietly(f);
            } else {
                refCounts.put(f, nc);
            }
        }
    }

    private void deleteQuietly(String f) {
        try {
            directory.deleteFile(f);
        } catch (NoSuchFileException ignored) {
        } catch (IOException ignored) {
        }
    }
}
