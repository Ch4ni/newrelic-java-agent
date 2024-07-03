/*
 *
 *  * Copyright 2023 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.nr.instrumentation;

import com.newrelic.agent.introspec.ExternalRequest;
import com.newrelic.agent.introspec.HttpTestServer;
import com.newrelic.agent.introspec.InstrumentationTestConfig;
import com.newrelic.agent.introspec.InstrumentationTestRunner;
import com.newrelic.agent.introspec.Introspector;
import com.newrelic.agent.introspec.MetricsHelper;
import com.newrelic.agent.introspec.SpanEvent;
import com.newrelic.agent.introspec.SpanEventsHelper;
import com.newrelic.agent.introspec.TransactionEvent;
import com.newrelic.agent.introspec.internal.HttpServerRule;
import com.newrelic.agent.model.SpanCategory;
import com.newrelic.api.agent.Trace;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(InstrumentationTestRunner.class)
@InstrumentationTestConfig(includePrefixes = { "org.springframework" }, configName = "spans.yml")
public class WebClientDtTest {

    private static final int TIMEOUT = 3000;

    @ClassRule
    public static HttpServerRule server = new HttpServerRule();
    private static URI dtEndpoint;
    private static String host;

    @BeforeClass
    public static void before() {
        // This is here to prevent reactor.util.ConsoleLogger output from taking over your screen
        System.setProperty("reactor.logging.fallback", "JDK");
        try {
            dtEndpoint = server.getEndPoint();
            host = dtEndpoint.getHost();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    @AfterClass
    public static void after() {
        server.shutdown();
    }

    @Test
    public void testDt() {
        Introspector introspector = InstrumentationTestRunner.getIntrospector();

        final String response = makeDtRequest(dtEndpoint).block().bodyToMono(String.class).block();
        assertEquals("<html><body><h1>SuccessfulResponse</h1>\n</body></html>\n", response);

        // transaction
        String txName = "OtherTransaction/Custom/com.nr.instrumentation.WebClientDtTest/makeDtRequest";
        assertEquals(2, introspector.getFinishedTransactionCount(
                TIMEOUT)); // One transaction is the one we care about and the other is the server-side CAT tx
        Collection<String> names = introspector.getTransactionNames();
        assertEquals(2, names.size());
        assertTrue(names.contains(server.getServerTransactionName()));
        assertTrue(names.contains(txName));

        // scoped metrics
        assertEquals(1, MetricsHelper.getScopedMetricCount(txName, "External/" + host + "/Spring-WebClient/exchange"));
        assertEquals(1,
                MetricsHelper.getScopedMetricCount(txName, "Java/com.nr.instrumentation.WebClientDtTest/makeDtRequest"));

        verifyUnscopedMetrics();
        verifyTransactionEvents(introspector, txName);
        verifyHttpSpan();

        // external request information
        verifyExternalRequest(introspector, txName);
    }

    @Test
    public void testDtBaseUrl() {
        Introspector introspector = InstrumentationTestRunner.getIntrospector();

        final String response = makeBaseUrlDtRequest(dtEndpoint).block().bodyToMono(String.class).block();
        assertEquals("<html><body><h1>SuccessfulResponse</h1>\n</body></html>\n", response);

        // transaction
        String txName = "OtherTransaction/Custom/com.nr.instrumentation.WebClientDtTest/makeBaseUrlDtRequest";
        assertEquals(2, introspector.getFinishedTransactionCount(
                TIMEOUT)); // One transaction is the one we care about and the other is the server-side CAT tx
        Collection<String> names = introspector.getTransactionNames();
        assertEquals(2, names.size());
        assertTrue(names.contains(server.getServerTransactionName()));
        assertTrue(names.contains(txName));

        // scoped metrics
        assertEquals(1, MetricsHelper.getScopedMetricCount(txName, "External/" + host + "/Spring-WebClient/exchange"));
        assertEquals(1,
                MetricsHelper.getScopedMetricCount(txName, "Java/com.nr.instrumentation.WebClientDtTest/makeBaseUrlDtRequest"));

        verifyUnscopedMetrics();
        verifyTransactionEvents(introspector, txName);
        verifyHttpSpan();
        verifyExternalRequest(introspector, txName);
    }

    private static void verifyUnscopedMetrics() {
        assertEquals(1, MetricsHelper.getUnscopedMetricCount("External/" + host + "/Spring-WebClient/exchange"));
        assertEquals(1, MetricsHelper.getUnscopedMetricCount("External/" + host + "/all"));
        assertEquals(1, MetricsHelper.getUnscopedMetricCount("External/all"));
        assertEquals(1, MetricsHelper.getUnscopedMetricCount("External/allOther"));
    }

    private static void verifyTransactionEvents(Introspector introspector, String txName) {
        Collection<TransactionEvent> transactionEvents = introspector.getTransactionEvents(txName);
        assertEquals(1, transactionEvents.size());
        TransactionEvent transactionEvent = transactionEvents.iterator().next();
        assertEquals(1, transactionEvent.getExternalCallCount());
        assertTrue(transactionEvent.getExternalDurationInSec() > 0);
    }

    private static void verifyHttpSpan() {
        // spans
        Collection<SpanEvent> httpSpans = SpanEventsHelper.getSpanEventsByCategory(SpanCategory.http);
        assertEquals(1, httpSpans.size());
        SpanEvent httpSpan = httpSpans.iterator().next();
        assertEquals(200, httpSpan.getStatusCode().intValue());
        assertEquals("Spring-WebClient", httpSpan.getHttpComponent());
        assertEquals("exchange", httpSpan.getHttpMethod());
        assertEquals(dtEndpoint.toString(), httpSpan.getHttpUrl());
        assertNull(httpSpan.getStatusText()); // Spring Webclient does not provide the status text
    }

    private static void verifyExternalRequest(Introspector introspector, String txName) {
        Collection<ExternalRequest> externalRequests = introspector.getExternalRequests(txName);
        assertEquals(1, externalRequests.size());
        ExternalRequest externalRequest = externalRequests.iterator().next();
        assertEquals(1, externalRequest.getCount());
        assertEquals(host, externalRequest.getHostname());
    }

    @Trace(dispatcher = true)
    public Mono<ClientResponse> makeDtRequest(URI uri) {
        WebClient webClient = WebClient.builder().build();
        return webClient.get().uri(uri).header(HttpTestServer.DO_CAT, "true").exchange();
    }

    @Trace(dispatcher = true)
    public Mono<ClientResponse> makeBaseUrlDtRequest(URI uri) {
        WebClient webClient = WebClient.builder().baseUrl(uri.toString()).build();
        return webClient.get().header(HttpTestServer.DO_CAT, "true").exchange();
    }
}
