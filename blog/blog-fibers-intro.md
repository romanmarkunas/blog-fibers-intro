# Introduction to Java coroutines

Many time I find an ExecutorService with 400 threads in Java code and it leaves This is usually introduced as necessary evil to retain application responsiveness while being blocked by downstream. However there is another way of achieving responsiveness without spinning up expensive OS-level threads.

Let's consider a ["spherical example in vacuum"](https://en.wikipedia.org/wiki/Spherical_cow) here. BTW, all code can be found [here](https://github.com/romanmarkunas/blog-fibers-intro).

## Slow service for testing purposes

Suppose we have microservice architecture application where service A must query service B before it can respond to customer, e.g. Navigation service providing some path between 2 locations and depending on Traffic service which collects data on current traffic congestion.

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

If you are unfamiliar with JAX-RS (let those servlets go, it's 2018), [here](https://jersey.github.io/documentation/latest/jaxrs-resources.html) is a little description.

This resource accepts `GET` requests and having that it sleeps for 1 second and then returns body containing invocation number. That number will be useful later to visualize what happened during test runs.

I'll use Dropwizard framework to create a service (mainly because it requires simpler setup for embedding app into test), however syntax must be obvious to Spring boot users as well. I will not post it here because service implementation is out of scope of this post.

## Test setup

Let's emulate our calls to our slow service in test suite. Following handy Dropwizard rule will create a fresh embedded server for each test:

```java
@Rule
public DropwizardAppRule<Configuration> app = new DropwizardAppRule<>(
        SlowApplication.class,
        ResourceHelpers.resourceFilePath("slow.yml"));
```

Now let's make our call routine which will be shared between thread and coroutine example:

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

Here we use Jersey client to make calls to service. Note that we record both time when routine started relative to test start time (`baseline`) and routine execution duration. Ignore `@Suspendable` for now, we'll come back to it later.

For testing purposes let's use a smaller thread pool of size 2:
```java
@Before
public void setUp() {
    this.executor = Executors.newFixedThreadPool(2);
}    `
```

## Blocking example

```java
@Test
public void threads() throws Exception {
    long baseline = System.currentTimeMillis();
    for (int i = 0; i < 10; i++) {
        this.executor.execute(() ->
                longTask(baseline, ClientBuilder.newClient()));
    }
    this.executor.shutdown();
    this.executor.awaitTermination(30, TimeUnit.SECONDS);
    System.out.println(String.format(
            "Thread execution took %d ms",
            System.currentTimeMillis() - baseline));
}
```

If we run this, we get something like:
```
Invocation 2 started at 104 and finished within 1831 ms
Invocation 1 started at 112 and finished within 1828 ms
Invocation 3 started at 1952 and finished within 1052 ms
Invocation 4 started at 1946 and finished within 1071 ms
Invocation 5 started at 3009 and finished within 1116 ms
...
Thread execution took 6646 ms
```

First 2 long tasks start immediately, since we have 2 threads available in pool. However other subsequent tasks cannot start and are put into thread pool's queue waiting to be picked up. This way  blocking calls stop execution and our application just burns CPU cycles doing nothing (but showing 100% utilization).

As mentioned before the natural response may be to increase number of threads in pool to have more threads in waiting state and have space for new incoming requests to start their execution ASAP. Let's see how this can be managed with coroutines.

## Coroutine example

Coroutine test is a bit more elaborate:

```java
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
```

This is due to fact that Quasar library does not have ExecutorService analogue for coroutines (called fibers in Quasar). This is somewhat logical and I had to put in future collection algorithm to make sure fibers will finish before test returns. In real-life scenarios you would not need to do that, because long running tasks idiomatically must be solved via `@AsyncResponse`. Codewise, thread example looks like fiber one less `Future` collection code.

One thing that is different is usage of asynchronous version of Jersey client. This is because asynchronous code of some libraries is automatically instrumented by Quasar COMSAT package (see below).

Let's run the test via gradle task (see below why) and voila:

```
Invocation 1 started at 135 and finished within 1209 ms
Invocation 2 started at 119 and finished within 1226 ms
Invocation 3 started at 197 and finished within 1152 ms
Invocation 4 started at 230 and finished within 1155 ms
Invocation 5 started at 238 and finished within 1147 ms
...
Fiber execution took 1661 ms
```

All invocations started at approximately same time and finished within 2 s. Int his case threads are not blocked by waiting and we could actually manage huge amount of requests with little threads (generally same size as number of CPU cores).

## Conclusion

To wrap it all in one sentence, threads must be used as a mean of parallelism, but coroutines as means of multitasking. Java thread is directly mapped onto OS thread and is abstraction over hardware implementation. It is optimal for high-throughput application to have same number of threads as cores, in order to have full parallelism and minimal switching. Coroutines allow to use that resource on full to avoid CPU being stalled when waiting for network or file IO.

## Objections?

#### This could be solved by async client only!

True, however when we have more code than this simple test and if we have multiple blocking calls during one operation, code becomes highly nested (see [callback hell](http://callbackhell.com/)).

During debugging unclear path of execution an obscure stack traces may be very confusing and counter productive. Coroutines allow to maintain sequential logic of execution, while still avoiding blocking.

#### Yet another library to learn

True. I will completely agree that for many applications there is no huge performance requirements and spinning 500 blocked threads is a viable solution.

All new libraries must be justified to be used and no performance optimizations must be done without defined performance requirements or before application logic is working as per requirements. However, once you need performance, using coroutines is crucial as it fits first optimization level (IO-bound optimizations).

#### Does this all come for free?

Unfortunately no. This quick blog post does not highlight some important details needed in order for example code to work. Since Java does not have language support for coroutines, some joggling is needed.

First, in order for coroutines to work compiled Java bytecode must be instrumented (changed). This is why if you try to launch tests directly via IntelliJ IDEA, Eclipse or other JUnit runner you will get
```
java.lang.IllegalArgumentException: Fiber class
com.romanmarkunas.blog.fibers.intro.server.SlowApplicationTest$1 has
not been instrumented.
```

Note this line in gradle `test` task:
```
jvmArgs "-javaagent:${configurations.quasar.iterator().next()}"
```

This adds javaagent argument to JVM launch line (more on java agents see [here](https://docs.oracle.com/javase/7/docs/api/java/lang/instrument/package-summary.html)). Here is very brief explanation of what it does. During class loading registered agents will scan loaded class for methods that have `@Suspended` annotation or `throws SuspendedException` in signature. Those will be modified to allow saving stack into memory and suspend execution during specific calls.

This means that there is some negative consequence:

1. overhead during classloading
2. developers must be careful to put all method signatures correctly
3. it creates connection between how code is written and launched
4. if you are using existing library to perform IO it may not have methods correctly annotated. If it's not the case you may need to create your own modification of library to be used together with fibers. In this example I used jersey async client library which already has modified fiber-friendly version:
```
testCompile 'co.paralleluniverse:comsat-jax-rs-client:0.7.0'
```
