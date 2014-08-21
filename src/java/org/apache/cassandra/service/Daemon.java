package org.apache.cassandra.service;

/**
 * @author Heiko Braun
 * @since 21/08/14
 */
public interface Daemon {
    void start();

    void stop();

    void destroy();

    void activate();

    void deactivate();
}
