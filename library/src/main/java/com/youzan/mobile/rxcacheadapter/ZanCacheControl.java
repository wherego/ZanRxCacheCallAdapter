/*
 * Copyright (C) 2015 ZanMobile, Inc.
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

import okhttp3.Headers;
import okhttp3.internal.http.HeaderParser;

/**
 * Created by ryan on 16/8/28.
 */

public class ZanCacheControl {

    public static String CACHE_HEADER = "ZanCache";

    String headerValue; // Lazily computed, null if absent.

    private final boolean noCache;
    private final int maxAgeSeconds;
    private final boolean onlyIfCached;
    private final boolean cacheBefore;
    private final boolean refreshCache;

    private ZanCacheControl(boolean noCache, int maxAgeSeconds, boolean onlyIfCached,
                            boolean cacheBefore, boolean refreshCache, String headerValue) {
        this.noCache = noCache;
        this.maxAgeSeconds = maxAgeSeconds;
        this.onlyIfCached = onlyIfCached;
        this.headerValue = headerValue;
        this.cacheBefore = cacheBefore;
        this.refreshCache = refreshCache;
    }

    public static ZanCacheControl createOnlyIfCache() {
        return new ZanCacheControl(false, 0, true, false, false, "only-if-cached");
    }

    public static ZanCacheControl createNoCache() {
        return new ZanCacheControl(true, 0, false, false, false, "no-cache");
    }

    public static ZanCacheControl createCacheBefore() {
        return new ZanCacheControl(true, 0, false, false, false, "cache-before");
    }

    public static ZanCacheControl createRefreshCache() {
        return new ZanCacheControl(false, 0, false, false, true, "refresh_cache");
    }

    public boolean noCache() {
        return noCache;
    }

    public int maxAgeSeconds() {
        return maxAgeSeconds;
    }

    public boolean onlyIfCached() {
        return onlyIfCached;
    }

    public boolean cacheBefore() {
        return cacheBefore;
    }

    public boolean isWriteCacheOpen() {
        return cacheBefore || refreshCache;
    }

    public boolean isReadCacheOpen() {
        return cacheBefore || onlyIfCached;
    }

    public boolean refreshCache() {
        return refreshCache;
    }

    public static ZanCacheControl parse(Headers headers) {
        boolean noCache = false;
        int maxAgeSeconds = -1;
        boolean onlyIfCached = false;
        boolean cacheBefore = false;
        String headerValue = null;
        boolean canUseHeaderValue = true;
        boolean refreshCache = false;

        for (int i = 0, size = headers.size(); i < size; i++) {
            String name = headers.name(i);
            String value = headers.value(i);

            if (name.equalsIgnoreCase(CACHE_HEADER)) {
                if (headerValue != null) {
                    // Multiple cache-control headers means we can't use the raw value.
                    canUseHeaderValue = false;
                } else {
                    headerValue = value;
                }
            }

            int pos = 0;
            while (pos < value.length()) {
                int tokenStart = pos;
                pos = HeaderParser.skipUntil(value, pos, "=,;");
                String directive = value.substring(tokenStart, pos).trim();
                String parameter;

                if (pos == value.length() || value.charAt(pos) == ',' || value.charAt(pos) == ';') {
                    pos++; // consume ',' or ';' (if necessary)
                    parameter = null;
                } else {
                    pos++; // consume '='
                    pos = HeaderParser.skipWhitespace(value, pos);

                    // quoted string
                    if (pos < value.length() && value.charAt(pos) == '\"') {
                        pos++; // consume '"' open quote
                        int parameterStart = pos;
                        pos = HeaderParser.skipUntil(value, pos, "\"");
                        parameter = value.substring(parameterStart, pos);
                        pos++; // consume '"' close quote (if necessary)

                        // unquoted string
                    } else {
                        int parameterStart = pos;
                        pos = HeaderParser.skipUntil(value, pos, ",;");
                        parameter = value.substring(parameterStart, pos).trim();
                    }
                }

                if ("no-cache".equalsIgnoreCase(directive)) {
                    noCache = true;
                } else if ("max-age".equalsIgnoreCase(directive)) {
                    maxAgeSeconds = HeaderParser.parseSeconds(parameter, -1);
                } else if ("only-if-cached".equalsIgnoreCase(directive)) {
                    onlyIfCached = true;
                } else if ("cache-before".equals(directive)) {
                    cacheBefore = true;
                } else if ("refresh_cache".equals(directive)) {
                    refreshCache = true;
                }
            }
        }
        if (!canUseHeaderValue) {
            headerValue = null;
        }
        return new ZanCacheControl(noCache, maxAgeSeconds, onlyIfCached, cacheBefore, refreshCache, headerValue);
    }

}
