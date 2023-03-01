/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.log4j2.appender;

import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.api.Message;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.Test;

public class PulsarAppenderClientConfTest extends AbstractPulsarAppenderTest {
    public PulsarAppenderClientConfTest() {
        super("PulsarAppenderClientConfTest.xml");
    }

    @Test
    public void testAppendWithClientConf() throws Exception {
        final Appender appender = ctx.getConfiguration().getAppender("PulsarAppenderWithClientConf");
        final LogEvent logEvent = createLogEvent();
        appender.append(logEvent);
        Message<byte[]> item;
        synchronized (history) {
            assertEquals(1, history.size());
            item = history.get(0);
        }
        assertNotNull(item);
        String msgKey = item.getKey();
        assertEquals(msgKey, "key");
        assertEquals(new String(item.getData(), StandardCharsets.UTF_8), LOG_MESSAGE);

        // verify authPlugin & authParams
        ArgumentCaptor<Authentication> authenticationCaptor = ArgumentCaptor.forClass(Authentication.class);
        verify(clientBuilder).authentication(authenticationCaptor.capture());
        Authentication authentication = authenticationCaptor.getValue();
        assertEquals(authentication.getAuthMethodName(), "token");
        AuthenticationDataProvider authData = authentication.getAuthData();
        assertTrue(authData.hasDataForHttp());
        Map<String, String> headers =
                authData.getHttpHeaders().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(headers.size(), 2);
        assertEquals(headers.get("X-Pulsar-Auth-Method-Name"), "token");
        assertEquals(headers.get("Authorization"), "Bearer TOKEN");

        // verify tlsAllowInsecureConnection
        verify(clientBuilder).allowTlsInsecureConnection(true);

        // verify tlsEnableHostnameVerification
        verify(clientBuilder).enableTlsHostnameVerification(true);

        // verify tlsTrustStorePassword
        ArgumentCaptor<String> tlsTrustStorePasswordCaptor = ArgumentCaptor.forClass(String.class);
        verify(clientBuilder).tlsTrustStorePassword(tlsTrustStorePasswordCaptor.capture());
        assertEquals(tlsTrustStorePasswordCaptor.getValue(), "_tlsTrustStorePassword_");

        // verify tlsTrustStorePath
        ArgumentCaptor<String> tlsTrustStorePathCaptor = ArgumentCaptor.forClass(String.class);
        verify(clientBuilder).tlsTrustStorePath(tlsTrustStorePathCaptor.capture());
        assertEquals(tlsTrustStorePathCaptor.getValue(), "_tlsTrustStorePath_");

        // verify tlsTrustCertsFilePath
        ArgumentCaptor<String> tlsTrustCertsFilePathCaptor = ArgumentCaptor.forClass(String.class);
        verify(clientBuilder).tlsTrustCertsFilePath(tlsTrustCertsFilePathCaptor.capture());
        assertEquals(tlsTrustCertsFilePathCaptor.getValue(), "_tlsTrustCertsFilePath_");

        // verify tlsTrustStoreType
        ArgumentCaptor<String> tlsTrustStoreTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(clientBuilder).tlsTrustStoreType(tlsTrustStoreTypeCaptor.capture());
        assertEquals(tlsTrustStoreTypeCaptor.getValue(), "_tlsTrustStoreType_");

        // verify useKeyStoreTls
        verify(clientBuilder).useKeyStoreTls(true);

        // verify loadConf
        ArgumentCaptor<Map<String, Object>> loadConfCaptor = ArgumentCaptor.forClass(Map.class);
        verify(clientBuilder).loadConf(loadConfCaptor.capture());
        Map<String, Object> conf = loadConfCaptor.getValue();
        assertEquals(conf.size(), 3);
        assertEquals(conf.get("numIoThreads"), "8");
        assertEquals(conf.get("connectionsPerBroker"), "5");
        assertEquals(conf.get("enableTransaction"), "true");

        // verify producer builder loadConf
        ArgumentCaptor<Map<String, Object>> producerLoadConfCaptor = ArgumentCaptor.forClass(Map.class);
        verify(producerBuilder).loadConf(producerLoadConfCaptor.capture());
        Map<String, Object> producerConf = producerLoadConfCaptor.getValue();
        assertEquals(producerConf.size(), 2);
        assertEquals(producerConf.get("maxPendingMessages"), "20000");
        assertEquals(producerConf.get("blockIfQueueFull"), "true");
    }

}