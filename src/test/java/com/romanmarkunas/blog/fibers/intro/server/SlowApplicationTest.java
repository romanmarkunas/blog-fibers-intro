package com.romanmarkunas.blog.fibers.intro.server;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberExecutorScheduler;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.fibers.ws.rs.client.AsyncClientBuilder;
import io.dropwizard.Configuration;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.*;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SlowApplicationTest {

    private static int CALLS = 10;

    private ExecutorService executor;


    @Rule
    public DropwizardAppRule<Configuration> app = new DropwizardAppRule<>(
            SlowApplication.class,
            ResourceHelpers.resourceFilePath("slow.yml"));


    @Before
    public void setUp() {
        this.executor = Executors.newFixedThreadPool(2);
    }


    @Test
    public void threads() throws Exception {
        long baseline = System.currentTimeMillis();
        for (int i = 0; i < CALLS; i++) {
            this.executor.execute(() ->
                    longTask(baseline, ClientBuilder.newClient()));
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

        for (int i = 0; i < CALLS; i++) {
            Fiber<Void> fiber = new Fiber<Void>(scheduler) {
                @Override
                protected Void run() throws SuspendExecution, InterruptedException {
                    longTask(baseline, AsyncClientBuilder.newClient());
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
    public void tearDown() {
        if (!this.executor.isShutdown()) {
            this.executor.shutdownNow();
        }
    }


    @Suspendable
    private void longTask(long baseline, Client client) {
        long start = millisSince(baseline);

        Response response = client
                .target(String.format(
                        "http://localhost:%d/slow-service",
                        app.getLocalPort()))
                .request()
                .get();
        int invocation = response.readEntity(int.class);
        response.close();

        System.out.println(String.format(
                "Invocation %d started at %d and finished within %d ms",
                invocation,
                start,
                millisSince(baseline) - start));
    }

    private static long millisSince(long since) {
        return System.currentTimeMillis() - since;
    }
}
