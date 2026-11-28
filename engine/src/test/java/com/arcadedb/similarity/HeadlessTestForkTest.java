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
package com.arcadedb.similarity;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test fork runs headless ({@code -Djava.awt.headless=true} in the root pom's surefire and failsafe argLine). The image hashes
 * use ImageIO, and on macOS the first AWT call in a non-headless JVM makes the whole fork a Dock application ("ForkedBooter"): a
 * Quit sent to it then ends the run through AWT's default quit handler, {@code System.exit(0)}, which surefire reports as "The
 * forked VM terminated without properly saying goodbye", exit code 0, no dump, in whichever class happens to be running (seen in
 * three full-lane runs on 2026-11-15). CI on Linux is headless anyway, so only a Mac run can lose it.
 */
class HeadlessTestForkTest {
  @Test
  void theTestForkIsHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).as("java.awt.headless in the root pom's surefire argLine").isTrue();
  }
}
