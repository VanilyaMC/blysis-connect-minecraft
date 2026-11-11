package com.uxplima.blysis.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.uxplima.blysis.application.port.Lanes;
import com.uxplima.blysis.domain.StatusPush;
import org.jspecify.annotations.Nullable;

/**
 * Turns the connection's steps into tasks: each step on a lane, the next one when the step says, and a status
 * push on a timer and shortly after a join or a quit.
 *
 * <p>No thread of this plugin's own exists. A long-poll holds one worker of the lane while the site holds it.
 */
public final class SiteLoop {

    /** The shortest time between two pushes caused by joins and quits. */
    private static final Duration PUSH_SETTLE = Duration.ofSeconds(1);

    private final StayConnected connection;
    private final Lanes lanes;
    private final Supplier<StatusPush> status;
    private final Supplier<Duration> statusInterval;
    private final AtomicBoolean pushPending = new AtomicBoolean();
    private volatile boolean running;
    private volatile Lanes.@Nullable Cancel next;
    private volatile Lanes.@Nullable Cancel statusTimer;

    public SiteLoop(
            StayConnected connection, Lanes lanes, Supplier<StatusPush> status, Supplier<Duration> statusInterval) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.status = Objects.requireNonNull(status, "status");
        this.statusInterval = Objects.requireNonNull(statusInterval, "statusInterval");
    }

    /** Starts calling the site. */
    public void start() {
        running = true;
        next = lanes.now(this::step);
    }

    /** Stops calling. A poll the site is holding ends when its connection is closed by the caller. */
    public void stop() {
        running = false;
        cancel(next);
        cancel(statusTimer);
    }

    /** Pushes who is online soon: once, a second after the first change of a burst. */
    public void statusSoon() {
        if (running && pushPending.compareAndSet(false, true)) {
            lanes.later(PUSH_SETTLE, () -> {
                pushPending.set(false);
                pushNow();
            });
        }
    }

    private void step() {
        if (!running) {
            return;
        }
        boolean greeted = connection.hello().isPresent();
        Optional<Duration> wait = connection.step();
        if (!greeted && connection.hello().isPresent()) {
            startStatusTimer();
            pushNow();
        }
        if (wait.isEmpty() || !running) {
            return;
        }
        Duration delay = wait.get();
        next = delay.isZero() ? lanes.now(this::step) : lanes.later(delay, this::step);
    }

    private void startStatusTimer() {
        Duration period = statusInterval.get();
        cancel(statusTimer);
        statusTimer = lanes.every(period, this::pushNow);
    }

    /**
     * One status push. A platform that could not say who is online skips this one: a throw here would end the
     * step before it schedules the next, and with it every delivery.
     */
    private void pushNow() {
        StatusPush push;
        try {
            push = status.get();
        } catch (RuntimeException unreadable) {
            return;
        }
        connection.pushStatus(push);
    }

    private static void cancel(Lanes.@Nullable Cancel handle) {
        if (handle != null) {
            handle.cancel();
        }
    }
}
