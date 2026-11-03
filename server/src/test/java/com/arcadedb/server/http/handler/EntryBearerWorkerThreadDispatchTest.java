/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.server.http.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Entry bearer that misses the verdict cache costs two server-to-server reads to Entry, which must not run on a
 * shared Undertow selector any more than a PBKDF2 may (issue #7785's rule). API and session tokens stay inline.
 */
class EntryBearerWorkerThreadDispatchTest {

  @Test
  void aBearerArcadeDbDidNotIssueIsDispatchedOffTheIoThread() {
    final HttpServerExchange exchange = new HttpServerExchange(null);
    exchange.getRequestHeaders().put(Headers.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature");
    assertThat(AbstractServerHttpHandler.authenticationNeedsWorkerThread(exchange)).isTrue();
  }

  @Test
  void arcadeDbTokensStillStayInline() {
    final HttpServerExchange apiToken = new HttpServerExchange(null);
    apiToken.getRequestHeaders().put(Headers.AUTHORIZATION, "Bearer at-0123456789abcdef");
    assertThat(AbstractServerHttpHandler.authenticationNeedsWorkerThread(apiToken)).isFalse();

    final HttpServerExchange sessionToken = new HttpServerExchange(null);
    sessionToken.getRequestHeaders().put(Headers.AUTHORIZATION, "Bearer AU-0123456789abcdef");
    assertThat(AbstractServerHttpHandler.authenticationNeedsWorkerThread(sessionToken)).isFalse();
  }
}
