package me.alwx.HttpServer;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.Map;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Random;


import androidx.annotation.Nullable;
import android.util.Log;

public class Server extends NanoHTTPD {
    private static final String TAG = "HttpServer";
    private static final String SERVER_EVENT_ID = "httpServerResponseReceived";
    public static String rootPath = null;

    private ReactContext reactContext;
    private Map<String, Response> responses;

    public Server(ReactContext context, int port) {
        super(port);
        reactContext = context;
        responses = new HashMap<>();

        Log.d(TAG, "Server started");
    }

    @Override
    public Response serve(IHTTPSession session) {
        // Log.d(TAG, "Request received!");

        Random rand = new Random();
        String requestId = String.format("%d:%d", System.currentTimeMillis(), rand.nextInt(1000000));
        String uri = session.getUri();
        File file = rootPath != null ? (
            hasParameterToFindFileWithoutExtension(session) ? 
            getFileWithoutExtension(uri)
            : new File(rootPath, uri))
            : null;

        if (file != null && file.exists()) {
            return serveFile(getMimeTypeForFile(uri), getHeaders(session), file, null, 3600);
        } else {
            WritableMap request;
            try {
                request = fillRequestMap(session, requestId);
            } catch (Exception e) {
                return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage()
                );
            }
    
            this.sendEvent(reactContext, SERVER_EVENT_ID, request);
    
            while (responses.get(requestId) == null) {
                try {
                    Thread.sleep(10);
                } catch (Exception e) {
                    Log.d(TAG, "Exception while waiting: " + e);
                }
            }
            Response response = responses.get(requestId);
            responses.remove(requestId);
            return response;
        }
    }

    private boolean hasParameterToFindFileWithoutExtension(IHTTPSession session) {
        String parameterKey = "checkFileWithoutExtension";
        if (session.getParameters().containsKey(parameterKey)) {
            String parameterValue = session.getParameters().get(parameterKey).get(0);
            return parameterValue.toLowerCase().equals("true");
        }
        return false;
    }

    private File getFileWithoutExtension(String uri) {
        String[] fileNameSplitedByDot = uri.split("\\.");
        File fileWithoutExtension = null;
        if (fileNameSplitedByDot.length > 0) {
            fileWithoutExtension = new File(rootPath, fileNameSplitedByDot[0]);
            if (fileWithoutExtension.exists()) {
                return fileWithoutExtension;
            }
        }
        return fileWithoutExtension;
    }


    public void respond(String requestId, int code, String type, String body, ReadableMap headers) {
        Response response = newFixedLengthResponse(Status.lookup(code), type, body);
        if (headers != null) {
            ReadableMapKeySetIterator iterator = headers.keySetIterator();
            while (iterator.hasNextKey()) {
                String key = iterator.nextKey();
                ReadableType readableType = headers.getType(key);
                switch (readableType) {
                    case Null:
                        response.addHeader(key, "");
                        break;
                    case Boolean:
                        response.addHeader(key, String.valueOf(headers.getBoolean(key)));
                        break;
                    case Number:
                        response.addHeader(key, String.valueOf(headers.getDouble(key)));
                        break;
                    case String:
                        response.addHeader(key, headers.getString(key));
                        break;
                    default:
                        Log.d(TAG, "Could not convert with key: " + key + ".");
                        break;
                }
            }
        }
        responses.put(requestId, response);
    }

    public void respondWithFile(String requestId, String filePath, ReadableMap range, int maxAge, ReadableMap headers) {
        Response response = serveFile(getMimeTypeForFile(filePath), headers, new File(filePath), range, maxAge);
        responses.put(requestId, response);
    }

    private ReadableMap getHeaders(IHTTPSession session) {
        WritableMap headers = Arguments.createMap();
        for (Map.Entry<String,String> entry : session.getHeaders().entrySet()) {
            headers.putString(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    private WritableMap fillRequestMap(IHTTPSession session, String requestId) throws Exception {
        Method method = session.getMethod();
        WritableMap request = Arguments.createMap();

        request.putString("url", session.getUri());
        request.putString("params", session.getQueryParameterString());
        request.putString("type", method.name());
        request.putMap("headers", getHeaders(session));
        request.putString("requestId", requestId);
        
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        if (files.size() > 0) {
          request.putString("postData", files.get("postData"));
        }

        return request;
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    private Response serveFile(String mime, ReadableMap header, File file, ReadableMap byteRange, int maxAge) {
        Response res;
        try {
            // Calculate etag
            String etag = Integer.toHexString((file.getAbsolutePath() +
                    file.lastModified() + "" + file.length()).hashCode());

            // Support (simple) skipping:
            long startFrom = 0;
            long endAt = -1;
            String range = header.getString("range");
            if (byteRange != null) {
                startFrom = Double.valueOf(byteRange.getDouble("from")).longValue();
                endAt = Double.valueOf(byteRange.getDouble("to")).longValue();
                range = String.format("bytes=%d-%d", startFrom, endAt);
            } else if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length());
                    int minus = range.indexOf('-');
                    try {
                        if (minus > 0) {
                            startFrom = Long.parseLong(range.substring(0, minus));
                            endAt = Long.parseLong(range.substring(minus + 1));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // Change return code and add Content-Range header when skipping is requested
            long fileLen = file.length();
            if (range != null && startFrom >= 0) {
                if (startFrom >= fileLen) {
                    res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
                    res.addHeader("Content-Range", "bytes 0-0/" + fileLen);
                    res.addHeader("ETag", etag);
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1;
                    }
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) {
                        newLen = 0;
                    }

                    final long dataLen = newLen;
                    FileInputStream fis = new FileInputStream(file) {
                        @Override
                        public int available() throws IOException {
                            return (int) dataLen;
                        }
                    };
                    fis.skip(startFrom);

                    res = newChunkedResponse(Response.Status.PARTIAL_CONTENT, mime, fis);
                    res.addHeader("Content-Length", "" + dataLen);
                    res.addHeader("Content-Range", "bytes " + startFrom + "-" +
                            endAt + "/" + fileLen);
                    res.addHeader("ETag", etag);
                }
            } else {
                if (etag.equals(header.getString("if-none-match")))
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "");
                else {
                    res = newFixedLengthResponse(Response.Status.OK, mime, new FileInputStream(file), file.length());
                    res.addHeader("Content-Length", "" + fileLen);
                    res.addHeader("ETag", etag);
                }
            }
            if (maxAge > 0) {
                res.addHeader("Cache-Control", String.format("max-age=%d, public", maxAge));
            } else {
                res.addHeader("Cache-Control", "no-cache");
            }
        } catch (IOException ioe) {
            res = newFixedLengthResponse(Status.FORBIDDEN, "", "Forbidden: Reading file failed");
        }

        return (res == null) ? newFixedLengthResponse(Status.NOT_FOUND, "", "Error 404: File not found") : res;
    }
}
