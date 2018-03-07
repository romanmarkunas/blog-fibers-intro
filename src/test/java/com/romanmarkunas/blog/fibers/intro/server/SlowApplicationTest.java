package com.romanmarkunas.blog.fibers.intro.server;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberExecutorScheduler;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.ws.rs.client.AsyncClientBuilder;
import io.dropwizard.Configuration;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SlowApplicationTest {

    private ExecutorService executor;


    @Rule
    public DropwizardAppRule<Configuration> appRule
            = new DropwizardAppRule<>(
            SlowApplication.class,
            ResourceHelpers.resourceFilePath("slow.yml"));


    @Before
    public void setUp() throws Exception {
        this.executor = Executors.newFixedThreadPool(2);
    }


    @Test
    public void threads() throws Exception {
        long baseline = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            this.executor.execute(() -> {
                long start = System.currentTimeMillis() - baseline;

                int invocation = appRule.client()
                        .target(String.format(
                                "http://localhost:%d/slow-service",
                                appRule.getLocalPort()))
                        .request()
                        .get()
                        .readEntity(int.class);

                long finish = System.currentTimeMillis() - baseline;

                System.out.println(String.format(
                        "Invocation %d started at %d and finished within %d ms",
                        invocation,
                        start,
                        finish - start));
            });
        }
        this.executor.shutdown();
        this.executor.awaitTermination(30, TimeUnit.SECONDS);
        System.out.println(String.format(
                "Thread execution took %d ms",
                System.currentTimeMillis() - baseline));
    }

    @Test
    public void fibers() throws Exception {
        long baseline = System.currentTimeMillis();
        List<Fiber<Void>> fibers = new ArrayList<>();
        FiberExecutorScheduler scheduler = new FiberExecutorScheduler(
                "default",
                this.executor);

        for (int i = 0; i < 10; i++) {
            Fiber<Void> fiber = new Fiber<Void>(scheduler) {
                @Override
                protected Void run() throws SuspendExecution, InterruptedException {
                    long start = System.currentTimeMillis() - baseline;

                    int invocation = AsyncClientBuilder.newClient()
                            .target(String.format(
                                    "http://localhost:%d/slow-service",
                                    appRule.getLocalPort()))
                            .request()
                            .get()
                            .readEntity(int.class);

                    long finish = System.currentTimeMillis() - baseline;

                    System.out.println(String.format(
                            "Invocation %d started at %d and finished within %d ms",
                            invocation,
                            start,
                            finish - start));

                    return null;
                }
            }.start();
            fibers.add(fiber);
        }

        for (Fiber fiber : fibers) {
            fiber.join(30, TimeUnit.SECONDS);
        }
        System.out.println(String.format(
                "Fiber execution took %d ms",
                System.currentTimeMillis() - baseline));
    }


    @After
    public void tearDown() throws Exception {
        if (!this.executor.isShutdown()) {
            this.executor.shutdownNow();
        }
    }
}
