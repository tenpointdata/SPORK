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
package com.arcadedb.spork.recon;

/**
 * A request the plugin refuses before it touches data: the HTTP status, a stable machine code Recon's {@code ApiError} switches on
 * ({@code contextRequired}, {@code purposeDenied}, ...) and a message for people.
 */
public class SporkRefusal extends RuntimeException {
  private final int    status;
  private final String code;

  public SporkRefusal(final int status, final String code, final String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  static SporkRefusal badRequest(final String code, final String message) {
    return new SporkRefusal(400, code, message);
  }

  static SporkRefusal notFound(final String code, final String message) {
    return new SporkRefusal(404, code, message);
  }
}
