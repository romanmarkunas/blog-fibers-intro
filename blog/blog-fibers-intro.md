# Introduction to Java coroutines

I don't know if it's just me, but every time I see an ExecutorService
with 400 threads, I have that "it's not ok" feeling. Sometimes I see
folk plugging multithreading without any reason, but sometimes it's a
necessary evil, needed to retain application responsive while being
blocked by downstream. However in there is another way of achieving
responsiveness without spinning up expensive OS-level threads.

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
These endpoints are directly mapped onto methods of the class using
`@Path` and method annotations, like `@GET`.

In this example our resource accepts only `GET` requests and when
having that it just sleeps for 1 second and then returns body containing
invocation number. That number will be useful later when we will try to
visualize what happened during test runs.

I'll use Dropwizard framework to create a service (mainly because it
requires simpler setup for embedding app into test), however syntax
must be obvious to Spring boot users as well. I will not post it here
because service implementation is out of scope of this post.

## Test setup

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
both time when routine started relative to test start time (baseline)
and routine execution duration. Ignore `@Suspendable` for now, I'll come
back to it later.

For testing purposes let's use a smaller thread pool of size 2:
```java
    @Before
    public void setUp() {
        this.executor = Executors.newFixedThreadPool(2);
    }    `
```

## Blocking example

And call our slow service:
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
```

First 2 long tasks start immediately, because we have 2 threads
available in pool. However other subsequent tasks cannot start and are
put into thread pool's queue waiting to be picked up. This way the whole
execution stops during blocking calls and our application just burns
CPU cycles doing nothing (but showing 100% utilization).

As mentioned before the natural response may be to increase number of
threads in pool to have more threads in waiting state and have space
for new incoming requests to start their execution asap. Let's see how
this can be managed with coroutines.

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

This is due to fact that Quasar library does not have ExecutorService
analogue for coroutines (called fibers in Quasar). This is somewhat
logical and I had to put in future collection algorithm to make sure
fibers have time to finish before test returns. In normal life scenarios
you would not need to do that, because long running tasks in running not
on acceptor pool must be solved via `@SuspendAsync` (TODO check name is
correct). You can see that thread example looks like fiber example less
Future collection code.

One thing that is different is usage of asynchronous version of
Jersey client. This is because asynchronous code of some libraries is
automatically instrumented by Quasar COMSAT package (see below).

Let's run the test via gradle task (see below why) and voila:

```
Invocation 1 started at 135 and finished within 1209 ms
Invocation 2 started at 119 and finished within 1226 ms
Invocation 3 started at 197 and finished within 1152 ms
Invocation 4 started at 230 and finished within 1155 ms
Invocation 5 started at 238 and finished within 1147 ms
...
```

All invocations start at approximately same time and all finish
within 1,5 s. As you can see 2 executor threads that we have are not
blocked by waiting and we could actually manage huge amount of requests
with small thread pool (ideally same number as CPU cores).

## Conclusion

To wrap it all in one sentence, threads must be used as a mean of
parallelism, but coroutines as means of multitasking. Java thread is
directly mapped onto OS thread and is abstraction over hardware
implementation. It is optimal for high-throughput application to have
same number of threads as cores, in order to have full parallelism
and minimal switching. Coroutines allow to use that resource on full
to avoid CPU being stalled in just waiting for network or file IO.

## Objections?

#### This could be solved by async client only!

True, however when we have more code than this simple test and if we
have multiple blocking calls during one operation, code becomes highly
nested (see [callback hell](TODO link) and [triangle of doom](TODO link)).
During debugging unclear path of execution an obscure stack traces may
be very confusing and counter productive. Coroutines allow to maintain
sequential logic of execution, while still executing in parallel.

#### Yet another library to learn

True. I will completely agree that for many applications
there is no huge performance requirements and spinning 500 blocked
threads is a viable solution. All new libraries must be justified to be
used and no performance optimizations must be done without defined
performance requirements and only after application logic is complete.
However, once you need performance, using coroutines is crucial as it
is first optimization level (IO-bound optimizations).

#### Does this all come for free?

Unfortunately no. This quick blog post does not highlight some
important details needed in order for example code to work. All this
is due to Java not having built-in language support for coroutines.

First, in order for coroutines to work the resulting Java bytecode must
be instrumented (changed). This is why if you try to launch tests
directly via IntelliJ IDEA, Eclipse or other JUnit runner you will get
```
TODO insert error message here
```

Not this line in gradle `test` task:
```
jvmArgs "-javaagent:${configurations.quasar.iterator().next()}"
```

This adds javaagent argument to JVM launch line (more on java agents see
[here](TODO link)). Here is very brief explanation of what it does.
During class loading (TODO research and clarify this) agent will scan
classes and bytecode of all methods that have `@Suspended` annotation
or have `throws SuspendedException` in their signature will be modified
to allow saving stack into memory and suspend execution during specific
calls.

This means that there is some negative consequence:
1) negligible overhead during classloading
1) developers must be careful to put all method signatures correctly
1) it creates connection between how code is written and launched
1) if you are using existing library to perform IO it may not have
methods correctly annotated. In this example I used jersey async client
library which already has modified fiber-friendly version imported via:
```
testCompile 'co.paralleluniverse:comsat-jax-rs-client:0.7.0'
```
If it's not the case you may need to create our own modification of
library to be used together with fibers.
