package org.apache.cassandra.service;

/**
 * @author Heiko Braun
 * @since 21/08/14
 */
public interface Server
{
    /**
     * Start the server.
     * This method shoud be able to restart a server stopped through stop().
     * Should throw a RuntimeException if the server cannot be started
     */
    public void start();

    /**
     * Stop the server.
     * This method should be able to stop server started through start().
     * Should throw a RuntimeException if the server cannot be stopped
     */
    public void stop();

    /**
     * Returns whether the server is currently running.
     */
    public boolean isRunning();
}
