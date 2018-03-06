package com.romanmarkunas.blog.fibers.intro.server;

import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class SlowApplication extends Application<Configuration> {

    public static void main(String[] args) throws Exception {
        new SlowApplication().run(args);
    }

    @Override
    public String getName() {
        return "Slow....poke!";
    }

    @Override
    public void initialize(Bootstrap<Configuration> bootstrap) {
        super.initialize(bootstrap);
    }

    @Override
    public void run(Configuration configuration, Environment environment) {
        environment.jersey().register(new SlowResource());
    }
}
