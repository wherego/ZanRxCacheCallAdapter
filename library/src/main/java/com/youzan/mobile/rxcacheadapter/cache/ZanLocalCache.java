/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.youzan.mobile.rxcacheadapter.cache;

import android.os.Environment;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.File;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.CipherSuite;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;
import okhttp3.internal.Platform;
import okhttp3.internal.Util;
import okhttp3.internal.http.CacheRequest;
import okhttp3.internal.http.OkHeaders;
import okhttp3.internal.http.StatusLine;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

/**
 * Created by ryan on 16/8/12.
 */

public class ZanLocalCache {

    private static final int VERSION = 201708;
    private static final int ENTRY_METADATA = 0;
    private static final int ENTRY_BODY = 1;
    private static final int ENTRY_COUNT = 2;

    private static String cacheDirPath;
    private DiskLruCache diskLruCache;

    private ZanLocalCache() {
        String cacheDir;
        if (cacheDirPath == null) {
            cacheDir = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator
                    + "ZanLocalCache";
        } else {
            cacheDir = cacheDirPath;
        }
        File file = new File(cacheDir);
        try {
            diskLruCache = DiskLruCache.open(file, VERSION, ENTRY_COUNT, 20 * 1024 * 1024);
        } catch (IOException e) {
        }
    }

    private static class InstanceHolder {
        private static final ZanLocalCache instance = new ZanLocalCache();
    }

    public static ZanLocalCache getInstance() {
        return InstanceHolder.instance;
    }

    public static void init(String cacheDir) {
        cacheDirPath = cacheDir;
    }

    public void put(Response response) {
        if (diskLruCache == null) {
            return;
        }

        String requestMethod = response.request().method();
        if (invalidatesCache(requestMethod)) {
            return;
        }

        if (OkHeaders.hasVaryAll(response)) {
            return;
        }

        Entry entry = new Entry(response);
        DiskLruCache.Editor editor = null;

        try {
            String key = requestToKey(response.request());
            editor = diskLruCache.edit(key);
            if (editor == null) {
                return;
            }
            entry.writeTo(editor);
            BufferedSink cacheOut = Okio.buffer(Okio.sink(editor.newOutputStream(ENTRY_BODY)));
            cacheOut.writeAll(response.body().source());
            cacheOut.flush();
            cacheOut.close();
            editor.commit();
        } catch (IOException e) {
            e.printStackTrace();
            if (editor != null) {
                try {
                    editor.abort();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    public Response get(Request request) {
        if (diskLruCache == null) {
            return null;
        }
        String key = requestToKey(request);
        DiskLruCache.Snapshot snapshot;
        Entry entry;
        try {
            snapshot = diskLruCache.get(key);
            if (snapshot == null) {
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        try {
            entry = new Entry(Okio.source(snapshot.getInputStream(ENTRY_METADATA)));
        } catch (IOException e) {
            Util.closeQuietly(snapshot);
            e.printStackTrace();
            return null;
        }

        Response response = entry.response(snapshot);
        if (!entry.matches(request)) {
            Util.closeQuietly(snapshot);
            return null;
        }

        return response;
    }

    public void removeRequest(Request request) {
        String key = requestToKey(request);
        try {
            diskLruCache.remove(key);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private String requestToKey(Request request) {
        HttpUrl.Builder urlBuilder = request.url().newBuilder();
        urlBuilder.removeAllQueryParameters("access_token");
        return Utils.md5Hex(urlBuilder.build().toString());
    }

    private boolean invalidatesCache(String method) {
        return !method.toUpperCase().equals("GET") && !method.toUpperCase().equals("POST");
    }

    private final class CacheRequestImpl implements CacheRequest {
        private final DiskLruCache.Editor editor;
        private Sink cacheOut;
        private boolean done;
        private Sink body;

        public CacheRequestImpl(final DiskLruCache.Editor editor) throws IOException {
            this.editor = editor;
            this.cacheOut = Okio.sink(editor.newOutputStream(ENTRY_BODY));
            this.body = new ForwardingSink(cacheOut) {
                @Override public void close() throws IOException {
                    synchronized (ZanLocalCache.this) {
                        if (done) {
                            return;
                        }
                        done = true;
                        // writeSuccessCount++;
                    }
                    super.close();
                    editor.commit();
                }
            };
        }

        @Override public void abort() {
            synchronized (ZanLocalCache.this) {
                if (done) {
                    return;
                }
                done = true;
                // writeAbortCount++;
            }
            Util.closeQuietly(cacheOut);
            try {
                editor.abort();
            } catch (IOException ignored) {
            }
        }

        @Override public Sink body() {
            return body;
        }
    }

    private static final class Entry {
        /** Synthetic response header: the local time when the request was sent. */
        private static final String SENT_MILLIS = Platform.get().getPrefix() + "-Sent-Millis";

        /** Synthetic response header: the local time when the response was received. */
        private static final String RECEIVED_MILLIS = Platform.get().getPrefix() + "-Received-Millis";

        private final String url;
        private final Headers varyHeaders;
        private final String requestMethod;
        private final Protocol protocol;
        private final int code;
        private final String message;
        private final Headers responseHeaders;
        private final Handshake handshake;
        private final long sentRequestMillis;
        private final long receivedResponseMillis;

        /**
         * Reads an entry from an input stream. A typical entry looks like this:
         * <pre>{@code
         *   http://google.com/foo
         *   GET
         *   2
         *   Accept-Language: fr-CA
         *   Accept-Charset: UTF-8
         *   HTTP/1.1 200 OK
         *   3
         *   Content-Type: image/png
         *   Content-Length: 100
         *   Cache-Control: max-age=600
         * }</pre>
         *
         * <p>A typical HTTPS file looks like this:
         * <pre>{@code
         *   https://google.com/foo
         *   GET
         *   2
         *   Accept-Language: fr-CA
         *   Accept-Charset: UTF-8
         *   HTTP/1.1 200 OK
         *   3
         *   Content-Type: image/png
         *   Content-Length: 100
         *   Cache-Control: max-age=600
         *
         *   AES_256_WITH_MD5
         *   2
         *   base64-encoded peerCertificate[0]
         *   base64-encoded peerCertificate[1]
         *   -1
         *   TLSv1.2
         * }</pre>
         * The file is newline separated. The first two lines are the URL and the request method. Next
         * is the number of HTTP Vary request header lines, followed by those lines.
         *
         * <p>Next is the response status line, followed by the number of HTTP response header lines,
         * followed by those lines.
         *
         * <p>HTTPS responses also contain SSL session information. This begins with a blank line, and
         * then a line containing the cipher suite. Next is the length of the peer certificate chain.
         * These certificates are base64-encoded and appear each on their own line. The next line
         * contains the length of the local certificate chain. These certificates are also
         * base64-encoded and appear each on their own line. A length of -1 is used to encode a null
         * array. The last line is optional. If present, it contains the TLS version.
         */
        public Entry(Source in) throws IOException {
            try {
                BufferedSource source = Okio.buffer(in);
                url = source.readUtf8LineStrict();
                requestMethod = source.readUtf8LineStrict();
                Headers.Builder varyHeadersBuilder = new Headers.Builder();
                int varyRequestHeaderLineCount = readInt(source);
                for (int i = 0; i < varyRequestHeaderLineCount; i++) {
                    varyHeadersBuilder.add(source.readUtf8LineStrict());
                }
                varyHeaders = varyHeadersBuilder.build();

                StatusLine statusLine = StatusLine.parse(source.readUtf8LineStrict());
                protocol = statusLine.protocol;
                code = statusLine.code;
                message = statusLine.message;
                Headers.Builder responseHeadersBuilder = new Headers.Builder();
                int responseHeaderLineCount = readInt(source);
                for (int i = 0; i < responseHeaderLineCount; i++) {
                    responseHeadersBuilder.add(source.readUtf8LineStrict());
                }
                String sendRequestMillisString = responseHeadersBuilder.get(SENT_MILLIS);
                String receivedResponseMillisString = responseHeadersBuilder.get(RECEIVED_MILLIS);
                responseHeadersBuilder.removeAll(SENT_MILLIS);
                responseHeadersBuilder.removeAll(RECEIVED_MILLIS);
                sentRequestMillis = sendRequestMillisString != null
                        ? Long.parseLong(sendRequestMillisString)
                        : 0L;
                receivedResponseMillis = receivedResponseMillisString != null
                        ? Long.parseLong(receivedResponseMillisString)
                        : 0L;
                responseHeaders = responseHeadersBuilder.build();

                if (isHttps()) {
                    String blank = source.readUtf8LineStrict();
                    if (blank.length() > 0) {
                        throw new IOException("expected \"\" but was \"" + blank + "\"");
                    }
                    String cipherSuiteString = source.readUtf8LineStrict();
                    CipherSuite cipherSuite = CipherSuite.forJavaName(cipherSuiteString);
                    List<Certificate> peerCertificates = readCertificateList(source);
                    List<Certificate> localCertificates = readCertificateList(source);
                    TlsVersion tlsVersion = !source.exhausted()
                            ? TlsVersion.forJavaName(source.readUtf8LineStrict())
                            : null;
                    handshake = Handshake.get(tlsVersion, cipherSuite, peerCertificates, localCertificates);
                } else {
                    handshake = null;
                }
            } finally {
                in.close();
            }
        }

        public Entry(Response response) {
            HttpUrl.Builder builder = response.request().url().newBuilder();
            builder.removeAllQueryParameters("access_token");
            this.url = builder.build().toString();
            this.varyHeaders = OkHeaders.varyHeaders(response);
            this.requestMethod = response.request().method();
            this.protocol = response.protocol();
            this.code = response.code();
            this.message = response.message();
            this.responseHeaders = response.headers();
            this.handshake = response.handshake();
            this.sentRequestMillis = response.sentRequestAtMillis();
            this.receivedResponseMillis = response.receivedResponseAtMillis();
        }

        public void writeTo(DiskLruCache.Editor editor) throws IOException {
            BufferedSink sink = Okio.buffer(Okio.sink(editor.newOutputStream(ENTRY_METADATA)));

            sink.writeUtf8(url)
                    .writeByte('\n');
            sink.writeUtf8(requestMethod)
                    .writeByte('\n');
            sink.writeDecimalLong(varyHeaders.size())
                    .writeByte('\n');
            for (int i = 0, size = varyHeaders.size(); i < size; i++) {
                sink.writeUtf8(varyHeaders.name(i))
                        .writeUtf8(": ")
                        .writeUtf8(varyHeaders.value(i))
                        .writeByte('\n');
            }

            sink.writeUtf8(new StatusLine(protocol, code, message).toString())
                    .writeByte('\n');
            sink.writeDecimalLong(responseHeaders.size() + 2)
                    .writeByte('\n');
            for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                sink.writeUtf8(responseHeaders.name(i))
                        .writeUtf8(": ")
                        .writeUtf8(responseHeaders.value(i))
                        .writeByte('\n');
            }
            sink.writeUtf8(SENT_MILLIS)
                    .writeUtf8(": ")
                    .writeDecimalLong(sentRequestMillis)
                    .writeByte('\n');
            sink.writeUtf8(RECEIVED_MILLIS)
                    .writeUtf8(": ")
                    .writeDecimalLong(receivedResponseMillis)
                    .writeByte('\n');

            if (isHttps()) {
                sink.writeByte('\n');
                sink.writeUtf8(handshake.cipherSuite().javaName())
                        .writeByte('\n');
                writeCertList(sink, handshake.peerCertificates());
                writeCertList(sink, handshake.localCertificates());
                // The handshake’s TLS version is null on HttpsURLConnection and on older cached responses.
                if (handshake.tlsVersion() != null) {
                    sink.writeUtf8(handshake.tlsVersion().javaName())
                            .writeByte('\n');
                }
            }
            sink.close();
        }

        private boolean isHttps() {
            return url.startsWith("https://");
        }

        private List<Certificate> readCertificateList(BufferedSource source) throws IOException {
            int length = readInt(source);
            if (length == -1) return Collections.emptyList(); // OkHttp v1.2 used -1 to indicate null.

            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                List<Certificate> result = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    String line = source.readUtf8LineStrict();
                    Buffer bytes = new Buffer();
                    bytes.write(ByteString.decodeBase64(line));
                    result.add(certificateFactory.generateCertificate(bytes.inputStream()));
                }
                return result;
            } catch (CertificateException e) {
                throw new IOException(e.getMessage());
            }
        }

        private void writeCertList(BufferedSink sink, List<Certificate> certificates)
                throws IOException {
            try {
                sink.writeDecimalLong(certificates.size())
                        .writeByte('\n');
                for (int i = 0, size = certificates.size(); i < size; i++) {
                    byte[] bytes = certificates.get(i).getEncoded();
                    String line = ByteString.of(bytes).base64();
                    sink.writeUtf8(line)
                            .writeByte('\n');
                }
            } catch (CertificateEncodingException e) {
                throw new IOException(e.getMessage());
            }
        }

        public boolean matches(Request request) {
            return url.equals(request.url().toString())
                    && requestMethod.equals(request.method());
                    // && OkHeaders.varyMatches(response, varyHeaders, request);
        }

        public Response response(DiskLruCache.Snapshot snapshot) {
            String contentType = responseHeaders.get("Content-Type");
            String contentLength = responseHeaders.get("Content-Length");
            Request cacheRequest = new Request.Builder()
                    .url(url)
                    .method(requestMethod, new CacheRequestBody())
                    .headers(varyHeaders)
                    .build();
            return new Response.Builder()
                    .request(cacheRequest)
                    .protocol(protocol)
                    .code(code)
                    .message(message)
                    .headers(responseHeaders)
                    .body(new CacheResponseBody(snapshot, contentType, contentLength))
                    .handshake(handshake)
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(receivedResponseMillis)
                    .build();
        }
    }

    private static int readInt(BufferedSource source) throws IOException {
        try {
            long result = source.readDecimalLong();
            String line = source.readUtf8LineStrict();
            if (result < 0 || result > Integer.MAX_VALUE || !line.isEmpty()) {
                throw new IOException("expected an int but was \"" + result + line + "\"");
            }
            return (int) result;
        } catch (NumberFormatException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static class CacheRequestBody extends RequestBody {
        @Override
        public MediaType contentType() {
            return MediaType.parse("application/json;charset=utf-8");
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {

        }
    }

    private static class CacheResponseBody extends ResponseBody {
        private final DiskLruCache.Snapshot snapshot;
        private final BufferedSource bodySource;
        private final String contentType;
        private final String contentLength;

        public CacheResponseBody(final DiskLruCache.Snapshot snapshot,
                                 String contentType, String contentLength) {
            this.snapshot = snapshot;
            this.contentType = contentType;
            this.contentLength = contentLength;

            Source source = Okio.source(snapshot.getInputStream(ENTRY_BODY));
            bodySource = Okio.buffer(new ForwardingSource(source) {
                @Override public void close() throws IOException {
                    snapshot.close();
                    super.close();
                }
            });
        }

        @Override public MediaType contentType() {
            return contentType != null ? MediaType.parse(contentType) : null;
        }

        @Override public long contentLength() {
            try {
                return contentLength != null ? Long.parseLong(contentLength) : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override public BufferedSource source() {
            return bodySource;
        }
    }

}
