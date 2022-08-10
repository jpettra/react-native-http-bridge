package me.alwx.HttpServer;

import android.content.Context;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactMethod;

import com.facebook.react.bridge.ReadableMap;

import java.io.IOException;

import android.util.Log;

public class HttpServerModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
    ReactApplicationContext reactContext;

    private static final String MODULE_NAME = "HttpServer";

    private static int port;
    private static Server server = null;

    public HttpServerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        reactContext.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    @ReactMethod
    public void start(int port, String serviceName) {
        Log.d(MODULE_NAME, "Initializing server...");
        HttpServerModule.port = port;
        startServer();
    }

    @ReactMethod
    public void stop() {
        Log.d(MODULE_NAME, "Stopping server...");
        stopServer();
    }

    @ReactMethod
    public void setRootDoc(String rootDoc) {
        Server.rootPath = rootDoc;
    }

    @ReactMethod
    public Boolean isRunning() {
        Log.d(MODULE_NAME, "isRunning server...");
        
        if (server != null) {
            return server.isAlive();
        }
        return false;
    }

    @ReactMethod
    public void respond(String requestId, int code, String type, String body, ReadableMap headers) {
        if (server != null) {
            server.respond(requestId, code, type, body, headers);
        }
    }

    @ReactMethod
    public void respondWithFile(String requestId, String filePath, ReadableMap range, int maxAge, ReadableMap headers) {
        if (server != null) {
            server.respondWithFile(requestId, filePath, range, maxAge, headers);
        }
    }

    @Override
    public void onHostResume() {

    }

    @Override
    public void onHostPause() {

    }

    @Override
    public void onHostDestroy() {
        stopServer();
    }

    private void startServer() {
        if (HttpServerModule.port == 0) {
            return;
        }
        if (server == null) {
            server = new Server(reactContext, port);
        }
        try {
            server.start();
        } catch (IOException e) {
            Log.e(MODULE_NAME, e.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
            port = 0;
        }
    }
}
