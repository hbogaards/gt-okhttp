/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2004-2011, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.http.okhttp3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import org.geotools.data.ows.AbstractOpenWebService;
import org.geotools.http.HTTPClient;
import org.geotools.http.HTTPConnectionPooling;
import org.geotools.http.HTTPProxy;
import org.geotools.http.HTTPResponse;
import org.geotools.util.factory.GeoTools;
import org.geotools.util.logging.Logging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Square OkHttp3 HTTP client based {@link HTTPClient} backed by a multithreaded connection
 * manager that allows to reuse connections to the backing server and to limit the {@link
 * #setMaxConnections(int) max number of concurrent connections}.
 *
 * <p>Java System properties {@code http.proxyHost}, {@code http.proxyPort}, {@code http.proxyUser},
 * and {@code http.proxyPassword} are respected.
 *
 * <p>Inspired by gt-http-commons.
 *
 * @author hbogaards
 * @see AbstractOpenWebService#setHttpClient(HTTPClient)
 */
public class OkHTTPClient implements HTTPClient, HTTPConnectionPooling, HTTPProxy {

    private OkHttpClient client;

    private ConnectionPool connectionPool;
    private Authenticator authenticator;

    private boolean tryGzip = true;
    private int maxConnections = 5;

    public OkHTTPClient() {
        connectionPool = new ConnectionPool(maxConnections, 5, TimeUnit.MINUTES);
        client = builder().build();
    }

    private OkHttpClient.Builder builder() {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .addNetworkInterceptor(
                                chain ->
                                        chain.proceed(
                                                chain.request()
                                                        .newBuilder()
                                                        .header(
                                                                "User-Agent",
                                                                String.format(
                                                                        "GeoTools/%s (%s)",
                                                                        GeoTools.getVersion(),
                                                                        this.getClass()
                                                                                .getSimpleName()))
                                                        .build()))
                        .connectionPool(connectionPool)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS);
        if (authenticator != null) {
            builder.authenticator(authenticator);
        }
        return builder;
    }

    @Override
    public HTTPResponse get(URL url) throws IOException {
        return get(url, null);
    }

    @Override
    public HTTPResponse get(URL url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder();
        builder.url(url);

        if (!tryGzip) {
            // Manually Set 'Accept-Encoding' header to 'identity' to disable transparant GZIP
            // handling of OkHttp3
            builder.addHeader("Accept-Encoding", "identity");
        }

        if (headers != null) {
            for (Map.Entry<String, String> headerNameValue : headers.entrySet()) {
                builder.addHeader(headerNameValue.getKey(), headerNameValue.getValue());
            }
        }

        Request request = builder.build();
        Response response = client.newCall(request).execute();
        return new OkHTTPResponse(response);
    }

    @Override
    public HTTPResponse post(URL url, InputStream postContent, String postContentType)
            throws IOException {

        Request.Builder builder = new Request.Builder();
        builder.url(url);

        final MediaType mediaType = MediaType.parse(postContentType);
        RequestBody body =
                new RequestBody() {
                    @Nullable
                    @Override
                    public MediaType contentType() {
                        return mediaType;
                    }

                    @Override
                    public void writeTo(@NotNull BufferedSink bufferedSink) throws IOException {
                        BufferedSource postbuffer = Okio.buffer(Okio.source(postContent));
                        bufferedSink.writeAll(postbuffer);
                    }
                };

        if (!tryGzip) {
            // Manually Set 'Accept-Encoding' header to 'identity' to disable transparant GZIP
            // handling of OkHttp3
            builder.addHeader("Accept-Encoding", "identity");
        }
        builder.post(body);
        Request request = builder.build();
        Response response = client.newCall(request).execute();
        return new OkHTTPResponse(response);
    }

    public Authenticator getAuthenticator() {
        return authenticator;
    }

    public void setAuthenticator(Authenticator authenticator) {
        client = client.newBuilder().authenticator(authenticator).build();
        this.authenticator = authenticator;
    }

    private BasicAuthenticator getBasicAuthenticator() {
        if (authenticator instanceof BasicAuthenticator) {
            return (BasicAuthenticator) authenticator;
        }
        return null;
    }

    @Override
    public String getUser() {
        return getBasicAuthenticator() != null ? getBasicAuthenticator().getUser() : null;
    }

    @Override
    public void setUser(String user) {
        String password = getPassword();
        BasicAuthenticator newAuthenticator = new BasicAuthenticator(user, password);
        if (newAuthenticator.getUser() != null && newAuthenticator.getPassword() != null) {
            client = client.newBuilder().authenticator(newAuthenticator).build();
        }
        authenticator = newAuthenticator;
    }

    @Override
    public String getPassword() {
        return getBasicAuthenticator() != null ? getBasicAuthenticator().getPassword() : null;
    }

    @Override
    public void setPassword(String password) {
        String user = getUser();
        BasicAuthenticator newAuthenticator = new BasicAuthenticator(user, password);
        if (newAuthenticator.getUser() != null && newAuthenticator.getPassword() != null) {
            client = client.newBuilder().authenticator(newAuthenticator).build();
        }
        authenticator = newAuthenticator;
    }

    @Override
    public int getConnectTimeout() {
        return client.connectTimeoutMillis() / 1000;
    }

    @Override
    public void setConnectTimeout(int connectTimeout) {
        client = client.newBuilder().connectTimeout(connectTimeout, TimeUnit.SECONDS).build();
    }

    @Override
    public int getReadTimeout() {
        return client.readTimeoutMillis() / 1000;
    }

    @Override
    public void setReadTimeout(int readTimeout) {
        client = client.newBuilder().readTimeout(readTimeout, TimeUnit.SECONDS).build();
    }

    @Override
    public void setTryGzip(boolean tryGzip) {
        this.tryGzip = tryGzip;
    }

    @Override
    public boolean isTryGzip() {
        return tryGzip;
    }

    @Override
    public int getMaxConnections() {
        return maxConnections;
    }

    @Override
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        this.connectionPool = new ConnectionPool(maxConnections, 5, TimeUnit.MINUTES);
        this.client = client.newBuilder().connectionPool(this.connectionPool).build();
    }

    @Override
    public void close() throws IOException {
        // Do something...
    }

    static class OkHTTPResponse implements HTTPResponse {

        private final Response response;
        private InputStream responseBodyAsStream;

        public OkHTTPResponse(final Response response) {
            this.response = response;
        }

        public int getStatusCode() {
            if (response != null) {
                return response.code();
            }
            return -1;
        }

        @Override
        public void dispose() {
            if (responseBodyAsStream != null) {
                try {
                    responseBodyAsStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }

            if (response != null) {
                response.close();
            }
        }

        @Override
        public String getContentType() {
            return getResponseHeader("Content-Type");
        }

        @Override
        public String getResponseHeader(final String headerName) {
            String responseHeader = response.header(headerName);
            return responseHeader;
        }

        @Override
        public InputStream getResponseStream() throws IOException {
            if (responseBodyAsStream == null) {
                if (response.body() != null) {
                    responseBodyAsStream = response.body().byteStream();
                }
            }
            return responseBodyAsStream;
        }

        /** @see org.geotools.http.HTTPResponse#getResponseCharset() */
        @Override
        public String getResponseCharset() {
            Charset encoding = null;
            if (response.body() != null && response.body().contentType() != null) {
                encoding = response.body().contentType().charset();
            }
            return encoding == null ? StandardCharsets.UTF_8.displayName() : encoding.displayName();
        }
    }

    private static class BasicAuthenticator implements Authenticator {

        private final Logger LOGGER = Logging.getLogger(getClass());

        private final String user;
        private final String password;
        private final String credential;

        BasicAuthenticator(String user, String password) {
            this.user = user;
            this.password = password;
            this.credential =
                    user != null && password != null ? Credentials.basic(user, password) : null;
        }

        public String getUser() {
            return user;
        }

        public String getPassword() {
            return password;
        }

        @Override
        public Request authenticate(Route route, Response response) throws IOException {
            if (response.request().header("Authorization") != null) {
                return null; // Give up, we've already attempted to authenticate.
            }

            LOGGER.fine("Authenticating for response: " + response);
            LOGGER.fine("Challenges: " + response.challenges());
            if (credential == null) {
                return null; // Give up, we don't have usable authentication
            }
            return response.request().newBuilder().header("Authorization", credential).build();
        }
    }
}
