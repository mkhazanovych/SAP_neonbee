package io.neonbee.internal.verticle;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import io.neonbee.NeonBee;
import io.neonbee.config.AuthHandlerConfig;
import io.neonbee.config.EndpointConfig;
import io.neonbee.config.ServerConfig;
import io.neonbee.config.ServerConfig.SessionHandling;
import io.neonbee.endpoint.Endpoint;
import io.neonbee.handler.ErrorHandler;
import io.neonbee.internal.handler.CacheControlHandler;
import io.neonbee.internal.handler.CorrelationIdHandler;
import io.neonbee.internal.handler.DefaultErrorHandler;
import io.neonbee.internal.handler.HooksHandler;
import io.neonbee.internal.handler.InstanceInfoHandler;
import io.neonbee.internal.handler.LoggerHandler;
import io.neonbee.internal.handler.NotFoundHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.ChainAuthHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import io.vertx.ext.web.sstore.ClusteredSessionStore;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;

/**
 * The {@link ServerVerticle} handles exposing all {@linkplain Endpoint endpoints} currently using the HTTP(S) protocol.
 *
 * This verticle handles the {@linkplain #config()} JSON object and parses it as a {@linkplain ServerConfig}.
 */
public class ServerVerticle extends AbstractVerticle {

    /**
     * Key where the ServerConfig is stored, if NeonBee is started with WEB profile.
     */
    public static final String SERVER_CONFIG_KEY = "__ServerVerticleConfig__";

    @VisibleForTesting
    static final AuthenticationHandler NOOP_AUTHENTICATION_HANDLER = new AuthenticationHandler() {
        @Override
        public void handle(RoutingContext routingContext) {
            routingContext.next();
        }
    };

    @VisibleForTesting
    static final String DEFAULT_ERROR_HANDLER_CLASS_NAME = DefaultErrorHandler.class.getName();

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private HttpServer httpServer;

    @Override
    public void start(Promise<Void> startPromise) {
        NeonBee.get(vertx).getLocalMap().put(SERVER_CONFIG_KEY, config());

        ServerConfig config = new ServerConfig(config());
        createRouter(config).compose(router -> {
            Optional<AuthenticationHandler> ach = createAuthChainHandler(config.getAuthChainConfig());
            return mountEndpoints(router, config.getEndpointConfigs(), ach, new HooksHandler()).onSuccess(v -> {
                // the NotFoundHandler fails the routing context finally.
                // To ensure that no handler will be added after it, it is added here.
                router.route().handler(new NotFoundHandler());
            }).compose(v -> createHttpServer(router, config));
        }).<Void>mapEmpty().onComplete(startPromise);
    }

    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        // Vert.x would close the HTTP server for us, however we would like to do some additional logging
        (httpServer != null ? httpServer.close().onComplete(result -> {
            LOGGER.info("HTTP server was stopped");
        }) : succeededFuture().<Void>mapEmpty()).onComplete(stopPromise);
    }

    private Future<Router> createRouter(ServerConfig config) {
        // the main router of the server verticle
        Router router = Router.router(vertx);

        // instead of creating new routes, vert.x recommends to add multiple handlers to one route instead. to prevent
        // sequence issues, block scope the variable to prevent using it after the endpoints have been mounted
        Route rootRoute = router.route();

        return createErrorHandler(config.getErrorHandlerClassName(), vertx).compose(errorHandler -> {
            rootRoute.failureHandler(errorHandler);
            rootRoute.handler(new LoggerHandler());
            rootRoute.handler(BodyHandler.create(false /* do not handle file uploads */));
            rootRoute.handler(new CorrelationIdHandler(config.getCorrelationStrategy()));
            rootRoute.handler(
                    TimeoutHandler.create(SECONDS.toMillis(config.getTimeout()), config.getTimeoutStatusCode()));
            rootRoute.handler(new CacheControlHandler());
            rootRoute.handler(new InstanceInfoHandler());

            createSessionStore(vertx, config.getSessionHandling()).map(SessionHandler::create)
                    .ifPresent(sessionHandler -> rootRoute
                            .handler(sessionHandler.setSessionCookieName(config.getSessionCookieName())));

            return succeededFuture(router);
        }).onFailure(e -> LOGGER.error("Router could not be created", e));
    }

    @VisibleForTesting
    // reason for PMD.EmptyCatchBlock empty cache block is "path-through" to fallback implementation and for
    // PMD.SignatureDeclareThrowsException it does not matter, if this private method does not expose any concrete
    // exceptions as the ServerVerticle start method anyways catches all exceptions in a generic try-catch block
    @SuppressWarnings({ "PMD.EmptyCatchBlock", "PMD.SignatureDeclareThrowsException" })
    static Future<ErrorHandler> createErrorHandler(String className, Vertx vertx) {
        try {
            Class<?> classObject = Class.forName(Optional.ofNullable(className).filter(Predicate.not(String::isBlank))
                    .orElse(DEFAULT_ERROR_HANDLER_CLASS_NAME));
            ErrorHandler eh = (ErrorHandler) classObject.getConstructor().newInstance();
            return eh.initialize(NeonBee.get(vertx))
                    .onFailure(t -> LOGGER.error("ErrorHandler could not be initialized", t));
        } catch (NoSuchMethodException e) {
            // do nothing here, if there is no such constructor, assume
            // the custom error handler class doesn't accept templates
            LOGGER.error("The custom ErrorHandler must offer a default constructor.", e);
            return failedFuture(e);
        } catch (Exception e) {
            return failedFuture(e);
        }
    }

    private Future<HttpServer> createHttpServer(Router router, ServerConfig config) {
        // Use the port passed via command line options, instead the configured one.
        Optional.ofNullable(NeonBee.get(vertx).getOptions().getServerPort()).ifPresent(config::setPort);

        return vertx.createHttpServer(config /* ServerConfig is a HttpServerOptions subclass */)
                .exceptionHandler(throwable -> {
                    LOGGER.error("HTTP Socket Exception", throwable);
                }).requestHandler(router).listen().onSuccess(httpServer -> {
                    this.httpServer = httpServer;
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("HTTP server started on port {}", httpServer.actualPort());
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("HTTP server configured with routes: {}",
                                router.getRoutes().stream().map(Route::toString).collect(Collectors.joining(",")));
                    }
                }).onFailure(cause -> LOGGER.error("HTTP server could not be started", cause));
    }

    /**
     * Mounts all endpoints as sub routers to the given router.
     *
     * @param router             the main router of the server verticle
     * @param endpointConfigs    a list of endpoint configurations to mount
     * @param defaultAuthHandler the fallback auth. handler in case no auth. handler is specified by the endpoint
     * @param hooksHandler       the "once per request" handler, to be executed after authentication on an endpoint
     */
    private Future<Void> mountEndpoints(Router router, List<EndpointConfig> endpointConfigs,
            Optional<AuthenticationHandler> defaultAuthHandler, HooksHandler hooksHandler) {
        if (endpointConfigs.isEmpty()) {
            LOGGER.warn("No endpoints configured");
            return succeededFuture();
        }

        // iterate the endpoint configurations, as order is important here!
        for (EndpointConfig endpointConfig : endpointConfigs) {
            String endpointType = endpointConfig.getType();
            if (Strings.isNullOrEmpty(endpointType)) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Endpoint with configuration {} is missing the 'type' field",
                            endpointConfig.toJson().encode());
                }
                return failedFuture(new IllegalArgumentException("Endpoint is missing the 'type' field"));
            }

            Endpoint endpoint;
            try {
                endpoint =
                        Class.forName(endpointType).asSubclass(Endpoint.class).getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                LOGGER.error("No class for endpoint type {}", endpointType, e);
                return failedFuture(new IllegalArgumentException("Endpoint class not found", e));
            } catch (ClassCastException e) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Endpoint type {} must implement {}", endpointType, Endpoint.class.getName(), e);
                }
                return failedFuture(
                        new IllegalArgumentException("Endpoint does not implement the Endpoint interface", e));
            } catch (NoSuchMethodException e) {
                LOGGER.error("Endpoint type {} must expose an empty constructor", endpointType, e);
                return failedFuture(new IllegalArgumentException("Endpoint does not expose an empty constructor", e));
            } catch (InvocationTargetException | IllegalAccessException | InstantiationException e) {
                LOGGER.error("Endpoint type {} could not be instantiated or threw an exception", endpointType, e);
                return failedFuture(Optional.ofNullable((Exception) e.getCause()).orElse(e));
            }

            EndpointConfig defaultEndpointConfig = endpoint.getDefaultConfig();
            if (!Optional.ofNullable(endpointConfig.isEnabled()).orElse(defaultEndpointConfig.isEnabled())) {
                LOGGER.info("Endpoint with type {} is disabled", endpointType);
                continue;
            }

            String endpointBasePath =
                    Optional.ofNullable(endpointConfig.getBasePath()).orElse(defaultEndpointConfig.getBasePath());
            if (!endpointBasePath.endsWith("/")) {
                endpointBasePath += "/";
            }

            JsonObject endpointAdditionalConfig = Optional.ofNullable(defaultEndpointConfig.getAdditionalConfig())
                    .map(JsonObject::copy).orElseGet(JsonObject::new);
            Optional.ofNullable(endpointConfig.getAdditionalConfig()).ifPresent(endpointAdditionalConfig::mergeIn);

            Router endpointRouter;
            try {
                endpointRouter = endpoint.createEndpointRouter(vertx, endpointBasePath, endpointAdditionalConfig);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize endpoint router for endpoint with type {} with configuration {}",
                        endpointType, endpointAdditionalConfig, e);
                return failedFuture(e);
            }

            Optional<AuthenticationHandler> endpointAuthHandler =
                    createAuthChainHandler(Optional.ofNullable(endpointConfig.getAuthChainConfig())
                            .orElse(defaultEndpointConfig.getAuthChainConfig())).or(() -> defaultAuthHandler);

            Route endpointRoute = router.route(endpointBasePath + "*");
            endpointAuthHandler.ifPresent(endpointRoute::handler);
            endpointRoute.handler(hooksHandler);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Mounting endpoint with type {} and configuration {}"
                                + "to base path {} using {} authentication handler",
                        endpointType, endpointConfig, endpointBasePath,
                        endpointAuthHandler.isPresent() ? "an" + endpointAuthHandler.get().getClass().getSimpleName()
                                : "no");
            }

            // requires a new route object, thus do not use the endpointRoute here, but call mountSubRouter instead
            router.mountSubRouter(endpointBasePath, endpointRouter);
        }

        // all endpoints have been mounted successfully
        return succeededFuture();
    }

    /**
     * Creates a {@linkplain SessionStore} based on the given {@linkplain ServerConfig} to use either local or clustered
     * session handling. If no session handling should be used, an empty optional is returned.
     *
     * @param vertx           the Vert.x instance to create the {@linkplain SessionStore} for
     * @param sessionHandling the session handling type
     * @return a optional session store, suitable for the given Vert.x instance and based on the provided config value
     *         (none/local/clustered). In case the session handling is set to clustered, but Vert.x does not run in
     *         clustered mode, fallback to the local session handling.
     */
    @VisibleForTesting
    static Optional<SessionStore> createSessionStore(Vertx vertx, SessionHandling sessionHandling) {
        switch (sessionHandling) {
        case LOCAL: // sessions are stored locally in a shared local map and only available on this instance
            return Optional.of(LocalSessionStore.create(vertx));
        case CLUSTERED: // sessions are stored in a distributed map which is accessible across the Vert.x cluster
            if (!vertx.isClustered()) { // Behaves like clustered in case that instance isn't clustered
                return Optional.of(LocalSessionStore.create(vertx));
            }
            return Optional.of(ClusteredSessionStore.create(vertx));
        default: /* nothing to do here, no session handling, so neither add a cookie, nor a session handler */
            return Optional.empty();
        }
    }

    /**
     * This method will return a full configured authentication handler with the following rules:
     * <ul>
     * <li>In case the auth. chain configuration is {@code null} an empty optional will be returned, meaning that either
     * no authentication should be used or NeonBee should fall back to the default authentication chain.
     * <li>In case the auth. chain configuration is empty, a dummy authentication handler, skipping authentication will
     * be returned which is used to state "no authentication" should be done for this endpoint.
     * <li>In case the auth. chain configuration contains exactly one configuration, the authentication handler will be
     * configured and returned by this method accordingly.
     * <li>In case multiple authentication handlers are configured in the authentication chain, an
     * {@link ChainAuthHandler} will be created with all configured authentication handlers in the order they appear in
     * the list of configurations.
     * </ul>
     * Overridden in NeonBeeTestBase and thus protected.
     *
     * @param authChainConfig a list of authentication handler configurations to create a auth. chain for
     * @return an optional auth. chain handler or an empty optional if no authentication should be used
     */
    @VisibleForTesting
    protected Optional<AuthenticationHandler> createAuthChainHandler(List<AuthHandlerConfig> authChainConfig) {
        if (authChainConfig == null) {
            // fallback to default authentication type (if available)
            return Optional.empty();
        } else if (authChainConfig.isEmpty()) {
            // important: empty means no authentication, while null / not specifying will fall back to the default
            return Optional.of(NOOP_AUTHENTICATION_HANDLER);
        }

        Stream<AuthenticationHandler> authHandlers =
                authChainConfig.stream().map(config -> config.createAuthHandler(vertx));
        if (authChainConfig.size() == 1) {
            return authHandlers.findFirst();
        } else {
            return Optional.of(authHandlers.reduce((AuthenticationHandler) ChainAuthHandler.any(),
                    (chainAuthHandler, authHandler) -> ((ChainAuthHandler) chainAuthHandler).add(authHandler)));
        }
    }
}
