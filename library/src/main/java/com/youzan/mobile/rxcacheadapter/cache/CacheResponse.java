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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import retrofit2.Converter;
import retrofit2.Response;
import retrofit2.Retrofit;

public class CacheResponse<T> {

    public Retrofit mRetrofit;

    public CacheResponse(Retrofit retrofit) {
        mRetrofit = retrofit;
    }

    T toResponse(ResponseBody body, Annotation[] annotations, Type responseType) throws IOException {
        Converter<ResponseBody, T> responseConverter = createResponseConverter(annotations, responseType);
        if (responseConverter != null) {
            return responseConverter.convert(body);
        } else {
            return null;
        }
    }

    private Converter<ResponseBody, T> createResponseConverter(Annotation[] annotations, Type responseType) {
        try {
            return mRetrofit.responseBodyConverter(responseType, annotations);
        } catch (RuntimeException e) { // Wide exception range because factories are user code.
            //throw methodError(e, "Unable to create converter for %s", responseType);
            e.printStackTrace();
            return null;
        }
    }

    public Response<T> parseResponse(okhttp3.Response rawResponse, Annotation[] annotations, Type responseType) {
        ResponseBody rawBody = rawResponse.body();

        // Remove the body's source (the only stateful object) so we can pass the response along.
        rawResponse = rawResponse.newBuilder()
                .body(new NoContentResponseBody(rawBody.contentType(), rawBody.contentLength()))
                .build();

        int code = rawResponse.code();
        if (code < 200 || code >= 300) {
            try {
                // Buffer the entire body to avoid future I/O.
                ResponseBody bufferedBody = buffer(rawBody);
                return Response.error(bufferedBody, rawResponse);
            } catch (IOException e) {
                return null;
            } finally {
                rawBody.close();
            }
        }

        if (code == 204 || code == 205) {
            return Response.success(null, rawResponse);
        }

        ExceptionCatchingRequestBody catchingBody = new ExceptionCatchingRequestBody(rawBody);
        try {
            T body = toResponse(catchingBody, annotations, responseType);
            return Response.success(body, rawResponse);
        } catch (Exception e) {
            // If the underlying source threw an exception, propagate that rather than indicating it was
            // a runtime exception.
            // catchingBody.throwIfCaught();
            e.printStackTrace();
            return null;
        }
    }

    static ResponseBody buffer(final ResponseBody body) throws IOException {
        Buffer buffer = new Buffer();
        body.source().readAll(buffer);
        return ResponseBody.create(body.contentType(), body.contentLength(), buffer);
    }

    static final class NoContentResponseBody extends ResponseBody {
        private final MediaType contentType;
        private final long contentLength;

        NoContentResponseBody(MediaType contentType, long contentLength) {
            this.contentType = contentType;
            this.contentLength = contentLength;
        }

        @Override public MediaType contentType() {
            return contentType;
        }

        @Override public long contentLength() {
            return contentLength;
        }

        @Override public BufferedSource source() {
            throw new IllegalStateException("Cannot read raw response body of a converted body.");
        }
    }

    static final class ExceptionCatchingRequestBody extends ResponseBody {
        private final ResponseBody delegate;
        IOException thrownException;

        ExceptionCatchingRequestBody(ResponseBody delegate) {
            this.delegate = delegate;
        }

        @Override public MediaType contentType() {
            return delegate.contentType();
        }

        @Override public long contentLength() {
            return delegate.contentLength();
        }

        @Override public BufferedSource source() {
            return Okio.buffer(new ForwardingSource(delegate.source()) {
                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    try {
                        return super.read(sink, byteCount);
                    } catch (IOException e) {
                        thrownException = e;
                        throw e;
                    }
                }
            });
        }

        @Override public void close() {
            delegate.close();
        }

        void throwIfCaught() throws IOException {
            if (thrownException != null) {
                throw thrownException;
            }
        }
    }
}
