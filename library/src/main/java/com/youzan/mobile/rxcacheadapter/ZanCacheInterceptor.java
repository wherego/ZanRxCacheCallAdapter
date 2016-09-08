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
package com.youzan.mobile.rxcacheadapter;

import android.content.Context;
import android.support.annotation.NonNull;

import com.youzan.mobile.rxcacheadapter.cache.ZanLocalCache;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 *
 * Response bodies can only be read once. If you consume the response body then you need to either
 * remove it from the response that you return or you need to replace it with something else.
 * In this instance, you can create a new response body using the string that you read so the
 * normal response-handling code has something to read from.
 *  Something like:
 * ----------------------------------
 *  return response.builder()
 *  .body(ResponseBody.create(response.body().contentType(), bodyString))
 *  .build();
 * ----------------------------------
 * Created by ryan on 16/8/12.
 */

public class ZanCacheInterceptor implements Interceptor {

    private ResponseAvailable responseAvailable;
    private Context context;

    public ZanCacheInterceptor(@NonNull Context context,
                               @NonNull ResponseAvailable availableCheck) {
        this.responseAvailable = availableCheck;
        this.context = context;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response;

        ZanCacheControl cacheControl;
        int netwrokState = NetworkUtils.getState(context);
        switch (netwrokState) {
            case NetworkUtils.NETWORK_NONE:
                cacheControl = ZanCacheControl.createOnlyIfCache();
                break;
            default:
                cacheControl = ZanCacheControl.parse(request.headers());
        }
        if (cacheControl.isCacheOpen()) {
            Request.Builder reqBuilder = request.newBuilder();
            reqBuilder.removeHeader(ZanCacheControl.CACHE_HEADER);
            Request newRequest = reqBuilder.build();

            Response checkResponse = chain.proceed(newRequest);
            String resStr = checkResponse.body().string();

            if (responseAvailable != null && responseAvailable.isResponseAvailable(resStr)) {
                ZanLocalCache.getInstance().put(checkResponse.newBuilder()
                        .body(ResponseBody.create(checkResponse.body().contentType(), resStr))
                        .build());
            }

            response = checkResponse.newBuilder()
                    .body(ResponseBody.create(checkResponse.body().contentType(), resStr))
                    .build();
        } else {
            response = chain.proceed(request);
        }
        return response;
    }

    public interface ResponseAvailable {
        boolean isResponseAvailable(String resStr);
    }
}
