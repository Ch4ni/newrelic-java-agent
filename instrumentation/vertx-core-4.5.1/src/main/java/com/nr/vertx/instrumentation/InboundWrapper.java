/*
 *
 *  * Copyright 2024 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.nr.vertx.instrumentation;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.InboundHeaders;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientResponse;

public class InboundWrapper implements InboundHeaders {

    private final MultiMap headers;

    public InboundWrapper(HttpClientResponse response) {
        this.headers = response.headers();
    }

    @Override
    public HeaderType getHeaderType() {
        return HeaderType.HTTP;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

}
