package com.naqqa.auth.sso.store;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.ratelimit.BucketState;
import com.naqqa.sso.ratelimit.FailureState;
import com.naqqa.sso.ratelimit.RateLimitStore;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class MongoRateLimitStore implements RateLimitStore {

    static final String COLLECTION = "sso_ratelimit";
    private static final String VERSION = "v";
    private static final List<String> FIELDS = List.of("tokens", "bucketUpdated", "window", "failureCount", "lastFailure");
    private static final int MAX_ATTEMPTS = 500;

    private final MongoTemplate template;
    private final Clock clock;
    private final Duration retention;
    private final ThreadLocal<Transaction> current = new ThreadLocal<>();

    public MongoRateLimitStore(MongoTemplate template, Clock clock, Duration retention) {
        this.template = template;
        this.clock = clock;
        this.retention = retention;
    }

    private static final class Pending {
        boolean reset;
        final Map<String, Object> sets = new LinkedHashMap<>();
    }

    private static final class Transaction {
        final Map<String, Document> reads = new HashMap<>();
        final Map<String, Pending> writes = new LinkedHashMap<>();
    }

    private Document load(String key) {
        Transaction tx = current.get();
        if (tx == null) {
            return template.findById(key, Document.class, COLLECTION);
        }
        if (!tx.reads.containsKey(key)) {
            tx.reads.put(key, template.findById(key, Document.class, COLLECTION));
        }
        return tx.reads.get(key);
    }

    private Object field(String key, String name) {
        Transaction tx = current.get();
        Pending p = tx == null ? null : tx.writes.get(key);
        if (p != null) {
            if (p.sets.containsKey(name)) {
                return p.sets.get(name);
            }
            if (p.reset) {
                return null;
            }
        }
        Document d = load(key);
        return d == null ? null : d.get(name);
    }

    private void write(String key, Map<String, Object> sets, boolean reset) {
        Transaction tx = current.get();
        if (tx == null) {
            Pending p = new Pending();
            p.reset = reset;
            p.sets.putAll(sets);
            for (int i = 0; i < 5 && !apply(key, p, null); i++) {
                Thread.onSpinWait();
            }
            return;
        }
        Pending p = tx.writes.computeIfAbsent(key, k -> new Pending());
        if (reset) {
            p.reset = true;
            p.sets.clear();
        }
        p.sets.putAll(sets);
    }

    private Update build(Pending p) {
        Update update = new Update()
                .inc(VERSION, 1)
                .setOnInsert("createdAt", MongoSupport.date(clock.instant()))
                .set("expiresAt", MongoSupport.date(clock.instant().plus(retention)));
        for (Map.Entry<String, Object> e : p.sets.entrySet()) {
            update.set(e.getKey(), e.getValue());
        }
        if (p.reset) {
            for (String f : FIELDS) {
                if (!p.sets.containsKey(f)) {
                    update.unset(f);
                }
            }
        }
        return update;
    }

    private boolean apply(String key, Pending p, Document read) {
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
        Criteria criteria = Criteria.where("_id").is(key);
        if (read != null) {
            long version = MongoSupport.number(read, VERSION);
            criteria = version == 0
                    ? criteria.orOperator(Criteria.where(VERSION).exists(false), Criteria.where(VERSION).is(0L))
                    : criteria.and(VERSION).is(version);
            options = FindAndModifyOptions.options().upsert(false).returnNew(true);
        }
        try {
            return template.findAndModify(new Query(criteria), build(p), options, Document.class, COLLECTION) != null;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private boolean commit(Transaction tx) {
        for (Map.Entry<String, Pending> e : tx.writes.entrySet()) {
            boolean ok;
            if (!tx.reads.containsKey(e.getKey())) {
                ok = apply(e.getKey(), e.getValue(), null);
            } else if (tx.reads.get(e.getKey()) == null) {
                ok = applyAbsent(e.getKey(), e.getValue());
            } else {
                ok = apply(e.getKey(), e.getValue(), tx.reads.get(e.getKey()));
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private boolean applyAbsent(String key, Pending p) {
        try {
            template.insert(initial(key, p), COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Document initial(String key, Pending p) {
        Document d = new Document("_id", key).append(VERSION, 1L)
                .append("createdAt", MongoSupport.date(clock.instant()))
                .append("expiresAt", MongoSupport.date(clock.instant().plus(retention)));
        d.putAll(p.sets);
        return d;
    }

    @Override
    public BucketState bucket(String key) {
        Object tokens = field(key, "tokens");
        if (tokens == null) {
            return null;
        }
        Object updated = field(key, "bucketUpdated");
        return new BucketState(((Number) tokens).doubleValue(), updated == null ? 0L : ((Number) updated).longValue());
    }

    @Override
    public void putBucket(String key, BucketState state) {
        write(key, Map.of("tokens", state.tokens(), "bucketUpdated", state.updatedMillis()), false);
    }

    @Override
    public List<Long> window(String key) {
        List<Long> out = new ArrayList<>();
        Object raw = field(key, "window");
        if (raw instanceof List<?> list) {
            for (Object n : list) {
                out.add(((Number) n).longValue());
            }
        }
        return out;
    }

    @Override
    public void putWindow(String key, List<Long> timestamps) {
        write(key, Map.of("window", new ArrayList<>(timestamps)), false);
    }

    public long increment(String key) {
        Update update = new Update().inc("count", 1L).inc(VERSION, 1)
                .setOnInsert("createdAt", MongoSupport.date(clock.instant()))
                .set("expiresAt", MongoSupport.date(clock.instant().plus(retention)));
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
        for (int attempt = 0; ; attempt++) {
            try {
                Document d = template.findAndModify(new Query(Criteria.where("_id").is(key)), update, options, Document.class, COLLECTION);
                return d == null ? 1 : MongoSupport.number(d, "count");
            } catch (DuplicateKeyException e) {
                if (attempt >= 2) {
                    throw e;
                }
            }
        }
    }

    public String lease(String key, Duration ttl) {
        String id = "ls:" + Bytes.hex(Hashes.sha256(Bytes.utf8(key))).substring(0, 32);
        String holder = Bytes.randomToken(12);
        Instant now = clock.instant();
        Query query = new Query(Criteria.where("_id").is(id).orOperator(Criteria.where("leaseUntil").exists(false),
                Criteria.where("leaseUntil").lte(MongoSupport.date(now))));
        Update update = new Update().set("leaseUntil", MongoSupport.date(now.plus(ttl))).set("holder", holder)
                .setOnInsert("createdAt", MongoSupport.date(now))
                .set("expiresAt", MongoSupport.date(now.plus(retention)));
        try {
            template.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, COLLECTION);
            return holder;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    public void release(String key, String holder) {
        String id = "ls:" + Bytes.hex(Hashes.sha256(Bytes.utf8(key))).substring(0, 32);
        template.updateFirst(new Query(Criteria.where("_id").is(id).and("holder").is(holder)),
                new Update().unset("leaseUntil").unset("holder"), COLLECTION);
    }

    public long clearAccount(String account) {
        String h = Bytes.hex(Hashes.sha256(Bytes.utf8(account == null ? "" : account.trim().toLowerCase(Locale.ROOT)))).substring(0, 32);
        Criteria criteria = new Criteria().orOperator(Criteria.where("_id").is("lt:a:" + h),
                Criteria.where("_id").regex("^" + Pattern.quote("lt:p:" + h + ":")));
        Update update = new Update().unset("failureCount").unset("lastFailure").inc(VERSION, 1);
        return template.updateMulti(new Query(criteria), update, COLLECTION).getModifiedCount();
    }

    @Override
    public FailureState failures(String key) {
        Object count = field(key, "failureCount");
        if (count == null) {
            return null;
        }
        Object last = field(key, "lastFailure");
        return new FailureState(((Number) count).intValue(), last == null ? 0L : ((Number) last).longValue());
    }

    @Override
    public void putFailures(String key, FailureState state) {
        write(key, Map.of("failureCount", state.count(), "lastFailure", state.lastFailureMillis()), false);
    }

    @Override
    public void remove(String key) {
        Transaction tx = current.get();
        if (tx == null) {
            Pending p = new Pending();
            p.reset = true;
            template.updateFirst(new Query(Criteria.where("_id").is(key)), build(p), COLLECTION);
            return;
        }
        write(key, Map.of(), true);
    }

    @Override
    public <R> R atomically(Supplier<R> action) {
        if (current.get() != null) {
            return action.get();
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Transaction tx = new Transaction();
            current.set(tx);
            R result;
            try {
                result = action.get();
            } finally {
                current.remove();
            }
            if (commit(tx)) {
                return result;
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2 + Math.min(attempt, 20)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SsoException("ratelimit.interrupted");
            }
        }
        throw new SsoException("ratelimit.contention");
    }
}
