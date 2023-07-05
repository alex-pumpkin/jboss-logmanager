/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.logmanager;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LoggingPermission;

import io.smallrye.common.constraint.Assert;
import io.smallrye.common.ref.Reference;
import io.smallrye.common.ref.References;

/**
 * A logging context, for producing isolated logging environments.
 */
@SuppressWarnings({ "unused", "WeakerAccess" })
public final class LogContext implements AutoCloseable {
    private static final LogContext SYSTEM_CONTEXT = new LogContext(false, discoverDefaultInitializer());

    private static LogContextInitializer discoverDefaultInitializer() {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            return AccessController
                    .doPrivileged((PrivilegedAction<LogContextInitializer>) LogContext::discoverDefaultInitializer0);
        } else {
            return discoverDefaultInitializer0();
        }
    }

    private static LogContextInitializer discoverDefaultInitializer0() {
        // allow exceptions to bubble up, otherwise logging won't work with no indication as to why
        final ServiceLoader<LogContextInitializer> loader = ServiceLoader.load(LogContextInitializer.class,
                LogContext.class.getClassLoader());
        final Iterator<LogContextInitializer> iterator = loader.iterator();
        return iterator.hasNext() ? iterator.next() : LogContextInitializer.DEFAULT;
    }

    static final Permission CREATE_CONTEXT_PERMISSION = new RuntimePermission("createLogContext", null);
    static final Permission SET_CONTEXT_SELECTOR_PERMISSION = new RuntimePermission("setLogContextSelector", null);
    static final Permission CONTROL_PERMISSION = new LoggingPermission("control", null);

    private final LoggerNode rootLogger;
    private final boolean strong;
    private final LogContextInitializer initializer;

    private final Set<LoggerNode> pinnedSet;

    private volatile Map<Logger.AttachmentKey<?>, Object> attachments;

    private static final VarHandle attachmentHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "attachments",
            VarHandle.class, LogContext.class, Map.class);

    /**
     * This lazy holder class is required to prevent a problem due to a LogContext instance being constructed
     * before the class init is complete.
     */
    private static final class LazyHolder {
        private static final HashMap<String, Reference<Level, Void>> INITIAL_LEVEL_MAP;

        private LazyHolder() {
        }

        private static void addStrong(Map<String, Reference<Level, Void>> map, Level level) {
            map.put(level.getName().toUpperCase(), References.create(Reference.Type.STRONG, level, null));
        }

        static {
            final HashMap<String, Reference<Level, Void>> map = new HashMap<String, Reference<Level, Void>>();
            addStrong(map, Level.OFF);
            addStrong(map, Level.ALL);
            addStrong(map, Level.SEVERE);
            addStrong(map, Level.WARNING);
            addStrong(map, Level.CONFIG);
            addStrong(map, Level.INFO);
            addStrong(map, Level.FINE);
            addStrong(map, Level.FINER);
            addStrong(map, Level.FINEST);

            addStrong(map, org.jboss.logmanager.Level.FATAL);
            addStrong(map, org.jboss.logmanager.Level.ERROR);
            addStrong(map, org.jboss.logmanager.Level.WARN);
            addStrong(map, org.jboss.logmanager.Level.INFO);
            addStrong(map, org.jboss.logmanager.Level.DEBUG);
            addStrong(map, org.jboss.logmanager.Level.TRACE);

            INITIAL_LEVEL_MAP = map;
        }
    }

    private final AtomicReference<Map<String, Reference<Level, Void>>> levelMapReference;
    // Guarded by treeLock
    private final Set<AutoCloseable> closeHandlers;

    /**
     * This lock is taken any time a change is made which affects multiple nodes in the hierarchy.
     */
    final ReentrantLock treeLock = new ReentrantLock();

    LogContext(final boolean strong, LogContextInitializer initializer) {
        this.initializer = initializer;
        this.strong = strong || initializer.useStrongReferences();
        levelMapReference = new AtomicReference<Map<String, Reference<Level, Void>>>(LazyHolder.INITIAL_LEVEL_MAP);
        rootLogger = new LoggerNode(this);
        closeHandlers = new LinkedHashSet<>();
        attachments = Map.of();
        pinnedSet = this.strong ? Set.of() : ConcurrentHashMap.newKeySet();
    }

    /**
     * Create a new log context. If a security manager is installed, the caller must have the {@code "createLogContext"}
     * {@link RuntimePermission RuntimePermission} to invoke this method.
     *
     * @param strong {@code true} if the context should use strong references, {@code false} to use (default) weak
     *               references for automatic logger GC
     * @return a new log context
     */
    public static LogContext create(boolean strong) {
        return create(strong, LogContextInitializer.DEFAULT);
    }

    /**
     * Create a new log context. If a security manager is installed, the caller must have the {@code "createLogContext"}
     * {@link RuntimePermission RuntimePermission} to invoke this method.
     *
     * @param strong      {@code true} if the context should use strong references, {@code false} to use (default) weak
     *                    references for automatic logger GC
     * @param initializer the log context initializer to use (must not be {@code null})
     * @return a new log context
     */
    public static LogContext create(boolean strong, LogContextInitializer initializer) {
        Assert.checkNotNullParam("initializer", initializer);
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CREATE_CONTEXT_PERMISSION);
        }
        return new LogContext(strong, initializer);
    }

    /**
     * Create a new log context. If a security manager is installed, the caller must have the {@code "createLogContext"}
     * {@link RuntimePermission RuntimePermission} to invoke this method.
     *
     * @return a new log context
     */
    public static LogContext create() {
        return create(false);
    }

    /**
     * Create a new log context. If a security manager is installed, the caller must have the {@code "createLogContext"}
     * {@link RuntimePermission RuntimePermission} to invoke this method.
     *
     * @param initializer the log context initializer to use (must not be {@code null})
     * @return a new log context
     */
    public static LogContext create(LogContextInitializer initializer) {
        return create(false, initializer);
    }

    // Attachment mgmt

    /**
     * Get the attachment value for a given key, or {@code null} if there is no such attachment.
     * Log context attachments are placed on the root logger and can also be accessed there.
     *
     * @param key the key
     * @param <V> the attachment value type
     * @return the attachment, or {@code null} if there is none for this key
     */
    @SuppressWarnings("unchecked")
    public <V> V getAttachment(Logger.AttachmentKey<V> key) {
        Assert.checkNotNullParam("key", key);
        return (V) attachments.get(key);
    }

    /**
     * Attach an object to this log context under a given key.
     * A strong reference is maintained to the key and value for as long as this log context exists.
     * Log context attachments are placed on the root logger and can also be accessed there.
     *
     * @param key   the attachment key
     * @param value the attachment value
     * @param <V>   the attachment value type
     * @return the old attachment, if there was one
     * @throws SecurityException        if a security manager exists and if the caller does not have
     *                                  {@code LoggingPermission(control)}
     * @throws IllegalArgumentException if the attachment cannot be added because the maximum has been reached
     */
    @SuppressWarnings("unchecked")
    public <V> V attach(Logger.AttachmentKey<V> key, V value) throws SecurityException {
        checkAccess();
        Assert.checkNotNullParam("key", key);
        Assert.checkNotNullParam("value", value);
        Map<Logger.AttachmentKey<?>, Object> oldAttachments;
        Map<Logger.AttachmentKey<?>, Object> newAttachments;
        V old;
        do {
            oldAttachments = attachments;
            newAttachments = new HashMap<>(oldAttachments);
            old = (V) newAttachments.put(key, value);
        } while (!attachmentHandle.compareAndSet(this, oldAttachments, Map.copyOf(newAttachments)));
        return old;
    }

    /**
     * Attach an object to this log context under a given key, if such an attachment does not already exist.
     * A strong reference is maintained to the key and value for as long as this log context exists.
     * Log context attachments are placed on the root logger and can also be accessed there.
     *
     * @param key   the attachment key
     * @param value the attachment value
     * @param <V>   the attachment value type
     * @return the current attachment, if there is one, or {@code null} if the value was successfully attached
     * @throws SecurityException        if a security manager exists and if the caller does not have
     *                                  {@code LoggingPermission(control)}
     * @throws IllegalArgumentException if the attachment cannot be added because the maximum has been reached
     */
    @SuppressWarnings("unchecked")
    public <V> V attachIfAbsent(Logger.AttachmentKey<V> key, V value) throws SecurityException {
        checkAccess();
        Assert.checkNotNullParam("key", key);
        Assert.checkNotNullParam("value", value);
        Map<Logger.AttachmentKey<?>, Object> oldAttachments;
        Map<Logger.AttachmentKey<?>, Object> newAttachments;
        do {
            oldAttachments = attachments;
            if (oldAttachments.containsKey(key)) {
                return (V) oldAttachments.get(key);
            }
            newAttachments = new HashMap<>(oldAttachments);
            newAttachments.put(key, value);
        } while (!attachmentHandle.compareAndSet(this, oldAttachments, Map.copyOf(newAttachments)));
        return null;
    }

    /**
     * Remove an attachment.
     * Log context attachments are placed on the root logger and can also be accessed there.
     *
     * @param key the attachment key
     * @param <V> the attachment value type
     * @return the old value, or {@code null} if there was none
     * @throws SecurityException if a security manager exists and if the caller does not have {@code LoggingPermission(control)}
     */
    @SuppressWarnings("unchecked")
    public <V> V detach(Logger.AttachmentKey<V> key) throws SecurityException {
        checkAccess();
        Assert.checkNotNullParam("key", key);
        Map<Logger.AttachmentKey<?>, Object> oldAttachments;
        Map<Logger.AttachmentKey<?>, Object> newAttachments;
        V result;
        do {
            oldAttachments = attachments;
            result = (V) oldAttachments.get(key);
            if (result == null) {
                return null;
            }
            final int size = oldAttachments.size();
            if (size == 1) {
                // special case - the new map is empty
                newAttachments = Map.of();
            } else {
                newAttachments = new HashMap<>(oldAttachments);
                newAttachments.remove(key);
            }
        } while (!attachmentHandle.compareAndSet(this, oldAttachments, Map.copyOf(newAttachments)));
        return result;
    }

    /**
     * Get a logger with the given name from this logging context.
     *
     * @param name the logger name
     * @return the logger instance
     * @see java.util.logging.LogManager#getLogger(String)
     */
    public Logger getLogger(String name) {
        return rootLogger.getOrCreate(name).createLogger();
    }

    /**
     * Get a logger with the given name from this logging context, if a logger node exists at that location.
     *
     * @param name the logger name
     * @return the logger instance, or {@code null} if no such logger node exists
     */
    public Logger getLoggerIfExists(String name) {
        final LoggerNode node = rootLogger.getIfExists(name);
        return node == null ? null : node.createLogger();
    }

    /**
     * Get a logger attachment for a logger name, if it exists.
     *
     * @param loggerName the logger name
     * @param key        the attachment key
     * @param <V>        the attachment value type
     * @return the attachment or {@code null} if the logger or the attachment does not exist
     */
    public <V> V getAttachment(String loggerName, Logger.AttachmentKey<V> key) {
        final LoggerNode node = rootLogger.getIfExists(loggerName);
        if (node == null)
            return null;
        return node.getAttachment(key);
    }

    /**
     * Get the level for a name.
     *
     * @param name the name
     * @return the level
     * @throws IllegalArgumentException if the name is not known
     */
    public Level getLevelForName(String name) throws IllegalArgumentException {
        if (name != null) {
            final Map<String, Reference<Level, Void>> map = levelMapReference.get();
            final Reference<Level, Void> levelRef = map.get(name);
            if (levelRef != null) {
                final Level level = levelRef.get();
                if (level != null) {
                    return level;
                }
            }
        }
        throw new IllegalArgumentException("Unknown level \"" + name + "\"");
    }

    /**
     * Register a level instance with this log context. The level can then be looked up by name. Only a weak
     * reference to the level instance will be kept. Any previous level registration for the given level's name
     * will be overwritten.
     *
     * @param level the level to register
     */
    public void registerLevel(Level level) {
        registerLevel(level, false);
    }

    /**
     * Register a level instance with this log context. The level can then be looked up by name. Any previous level
     * registration for the given level's name will be overwritten.
     *
     * @param level  the level to register
     * @param strong {@code true} to strongly reference the level, or {@code false} to weakly reference it
     */
    public void registerLevel(Level level, boolean strong) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CONTROL_PERMISSION);
        }
        for (;;) {
            final Map<String, Reference<Level, Void>> oldLevelMap = levelMapReference.get();
            final Map<String, Reference<Level, Void>> newLevelMap = new HashMap<>(oldLevelMap.size());
            for (Map.Entry<String, Reference<Level, Void>> entry : oldLevelMap.entrySet()) {
                final String name = entry.getKey();
                final Reference<Level, Void> levelRef = entry.getValue();
                if (levelRef.get() != null) {
                    newLevelMap.put(name, levelRef);
                }
            }
            newLevelMap.put(level.getName(),
                    References.create(strong ? Reference.Type.STRONG : Reference.Type.WEAK, level, null));
            if (levelMapReference.compareAndSet(oldLevelMap, newLevelMap)) {
                return;
            }
        }
    }

    /**
     * Unregister a previously registered level. Log levels that are not registered may still be used, they just will
     * not be findable by name.
     *
     * @param level the level to unregister
     */
    public void unregisterLevel(Level level) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CONTROL_PERMISSION);
        }
        for (;;) {
            final Map<String, Reference<Level, Void>> oldLevelMap = levelMapReference.get();
            final Reference<Level, Void> oldRef = oldLevelMap.get(level.getName());
            if (oldRef == null || oldRef.get() != level) {
                // not registered, or the registration expired naturally
                return;
            }
            final Map<String, Reference<Level, Void>> newLevelMap = new HashMap<>(oldLevelMap.size());
            for (Map.Entry<String, Reference<Level, Void>> entry : oldLevelMap.entrySet()) {
                final String name = entry.getKey();
                final Reference<Level, Void> levelRef = entry.getValue();
                final Level oldLevel = levelRef.get();
                if (oldLevel != null && oldLevel != level) {
                    newLevelMap.put(name, levelRef);
                }
            }
            newLevelMap.put(level.getName(), References.create(Reference.Type.WEAK, level, null));
            if (levelMapReference.compareAndSet(oldLevelMap, newLevelMap)) {
                return;
            }
        }
    }

    /**
     * Get the system log context.
     *
     * @return the system log context
     */
    public static LogContext getSystemLogContext() {
        return SYSTEM_CONTEXT;
    }

    /**
     * The default log context selector, which always returns the system log context.
     */
    public static final LogContextSelector DEFAULT_LOG_CONTEXT_SELECTOR = new LogContextSelector() {
        public LogContext getLogContext() {
            return SYSTEM_CONTEXT;
        }
    };

    private static volatile LogContextSelector logContextSelector = DEFAULT_LOG_CONTEXT_SELECTOR;

    /**
     * Get the currently active log context.
     *
     * @return the currently active log context
     */
    public static LogContext getLogContext() {
        return logContextSelector.getLogContext();
    }

    /**
     * Set a new log context selector. If a security manager is installed, the caller must have the
     * {@code "setLogContextSelector"}
     * {@link RuntimePermission RuntimePermission} to invoke this method.
     *
     * @param newSelector the new selector.
     */
    public static void setLogContextSelector(LogContextSelector newSelector) {
        if (newSelector == null) {
            throw new NullPointerException("newSelector is null");
        }
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(SET_CONTEXT_SELECTOR_PERMISSION);
        }
        logContextSelector = newSelector;
    }

    /**
     * Returns the currently set log context selector.
     *
     * @return the log context selector
     */
    public static LogContextSelector getLogContextSelector() {
        return logContextSelector;
    }

    @Override
    public void close() throws Exception {
        treeLock.lock();
        try {
            // First we want to close all loggers
            recursivelyClose(rootLogger);
            // Next process the close handlers associated with this log context
            for (AutoCloseable handler : closeHandlers) {
                handler.close();
            }
            final Map<?, ?> oldAttachments = (Map<?, ?>) attachmentHandle.getAndSet(this, Map.of());
            for (Object value : oldAttachments.values()) {
                if (value instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) value).close();
                    } catch (Exception ignore) {
                    }
                }
            }
            if (!pinnedSet.isEmpty()) {
                pinnedSet.clear();
            }
        } finally {
            treeLock.unlock();
        }
    }

    /**
     * Returns an enumeration of the logger names that have been created. This does not return names of loggers that
     * may have been garbage collected. Logger names added after the enumeration has been retrieved may also be added to
     * the enumeration.
     *
     * @return an enumeration of the logger names
     *
     * @see java.util.logging.LogManager#getLoggerNames()
     */
    public Enumeration<String> getLoggerNames() {
        return rootLogger.getLoggerNames();
    }

    /**
     * Adds a handler invoked during the {@linkplain #close() close} of this log context. The close handlers will be
     * invoked in the order they are added.
     * <p>
     * The loggers associated with this context will always be closed.
     * </p>
     *
     * @param closeHandler the close handler to use
     */
    public void addCloseHandler(final AutoCloseable closeHandler) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CONTROL_PERMISSION);
        }
        treeLock.lock();
        try {
            closeHandlers.add(closeHandler);
        } finally {
            treeLock.unlock();
        }
    }

    /**
     * Gets the current close handlers associated with this log context.
     *
     * @return the current close handlers
     */
    public Set<AutoCloseable> getCloseHandlers() {
        treeLock.lock();
        try {
            return new LinkedHashSet<>(closeHandlers);
        } finally {
            treeLock.unlock();
        }
    }

    /**
     * Clears any current close handlers associated with log context, then adds the handlers to be invoked during
     * the {@linkplain #close() close} of this log context. The close handlers will be invoked in the order they are
     * added.
     * <p>
     * The loggers associated with this context will always be closed.
     * </p>
     *
     * @param closeHandlers the close handlers to use
     */
    public void setCloseHandlers(final Collection<AutoCloseable> closeHandlers) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CONTROL_PERMISSION);
        }
        treeLock.lock();
        try {
            this.closeHandlers.clear();
            this.closeHandlers.addAll(closeHandlers);
        } finally {
            treeLock.unlock();
        }
    }

    private static SecurityException accessDenied() {
        return new SecurityException("Log context modification access denied");
    }

    static void checkAccess() {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CONTROL_PERMISSION);
        }
    }

    LoggerNode getRootLoggerNode() {
        return rootLogger;
    }

    ConcurrentMap<String, LoggerNode> createChildMap() {
        return strong ? new CopyOnWriteMap<String, LoggerNode>() : new CopyOnWriteWeakMap<String, LoggerNode>();
    }

    boolean pin(LoggerNode node) {
        return !strong && pinnedSet.add(node);
    }

    boolean unpin(LoggerNode node) {
        return !strong && pinnedSet.remove(node);
    }

    LogContextInitializer getInitializer() {
        return initializer;
    }

    private void recursivelyClose(final LoggerNode loggerNode) {
        assert treeLock.isHeldByCurrentThread();
        for (LoggerNode child : loggerNode.getChildren()) {
            recursivelyClose(child);
        }
        loggerNode.close();
    }
}
