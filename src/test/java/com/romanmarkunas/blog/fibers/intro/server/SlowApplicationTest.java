package com.romanmarkunas.blog.fibers.intro.server;

import io.dropwizard.Configuration;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SlowApplicationTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);


    @ClassRule
    public static final DropwizardAppRule<Configuration> appRule
            = new DropwizardAppRule<>(
            SlowApplication.class,
            ResourceHelpers.resourceFilePath("slow.yml"));


    @Test
    public void threads() throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            this.executor.execute(() -> {
                long start = System.currentTimeMillis() - now;

                int invocation = appRule
                        .client()
                        .target(String.format(
                                "http://localhost:%d/slow-service",
                                appRule.getLocalPort()))
                        .request()
                        .get()
                        .readEntity(int.class);

                long finish = System.currentTimeMillis() - now;

                System.out.println(String.format(
                        "Invocation %d started at %d and finished within %d ms",
                        invocation,
                        start,
                        finish - start));
            });
        }
        this.executor.shutdown();
        this.executor.awaitTermination(30, TimeUnit.SECONDS);
    }
}
