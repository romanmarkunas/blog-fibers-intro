# Introduction to Java coroutines

I don't know if it's just me, but every time I see an ExecutorService
with 400 threads, I have that "it's not ok" feeling. Sometimes I see
folk plugging multithreading without any reason, but sometimes it's a
necessary evil, needed to retain application responsive while being
blocked by downstream.

Let's consider a ["spherical example in vacuum"](TODO insert wiki link
to spherical cow here) here. BTW, all the code used in this blog-post
can be found in [this](TODO link to my github) repository.

## Slow service for testing purposes

Let's consider we have microservice architecture application and
during processing service A must retrieve some data from service B
before it will be able to finish processing. For example that could be
a Navigation service providing some path between 2 geographical
locations and it depends on Traffic service which is responsible for
collecting data on current traffic congestion.

We can emulate that Traffic service with contrived Jersey resource:

```java
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
```

In case you are unfamiliar with Jax-RS (let those servlets go, it's
2018 already!), here's a little description. The class represents a
group of endpoint located together in `/slow-service` path location.
In this example our resource accepts only `GET` requests and when
having that it just sleeps for 1 second and then returns body containing
invocation number. That number will be useful later when we will try to
visualize what happened during test runs.

I'll use Dropwizard framework to create a service (mainly because it
requires simpler setup for embedding app into test), however syntax
must be obvious to Spring boot users as well. I will not post it here
because service implementation is out of scope of this post.

## Blocking example

Let's emulate our calls to our slow service in test suite. To create
embedded server for test we can use handy Dropwizard rule:

```java
    @Rule
    public DropwizardAppRule<Configuration> app = new DropwizardAppRule<>(
            SlowApplication.class,
            ResourceHelpers.resourceFilePath("slow.yml"));
```

This will create a fresh embedded server for each of our tests.

Now let's make our call routine which will be shared between thread and
coroutine example:

```java
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
```

Here we use Jersey client to make calls to service. Note that we record
both time when routine started relative to baseline and routine
execution duration.