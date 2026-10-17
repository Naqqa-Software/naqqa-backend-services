package com.naqqa.auth.sso.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

abstract class MongoStoreTestBase {

    static final String URI = "mongodb://127.0.0.1:27017";

    static MongoClient client;
    static MongoTemplate template;
    static String database;
    static final Clock CLOCK = Clock.systemUTC();

    @BeforeAll
    static void connect() {
        MongoClient candidate = MongoClients.create(URI + "/?serverSelectionTimeoutMS=1500&connectTimeoutMS=1500");
        try {
            candidate.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (RuntimeException e) {
            candidate.close();
            Assumptions.abort("mongod not reachable at " + URI);
        }
        client = candidate;
        database = "sso_store_test_" + UUID.randomUUID().toString().replace("-", "");
        template = new MongoTemplate(client, database);
        SsoMongoIndexes.ensure(template);
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.getDatabase(database).drop();
            client.close();
        }
    }

    static <T> List<T> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
