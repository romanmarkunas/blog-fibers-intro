package com.romanmarkunas.blog.fibers.intro.server;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.concurrent.atomic.AtomicInteger;

@Path(SlowResource.PATH)
public class SlowResource {

    public static final String PATH = "/slow-service";

    private final AtomicInteger counter = new AtomicInteger(0);

    @GET
    public int slowServiceInvocations() throws InterruptedException {
        Thread.sleep(1000);
        return this.counter.incrementAndGet();
    }
}
