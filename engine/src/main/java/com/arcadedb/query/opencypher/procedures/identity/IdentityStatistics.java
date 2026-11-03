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
package com.arcadedb.query.opencypher.procedures.identity;

/**
 * The few special functions the identity match model needs, kept here rather than pulling in a statistics library.
 */
final class IdentityStatistics {
  private static final double[] A = { -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
      1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00 };
  private static final double[] B = { -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
      6.680131188771972e+01, -1.328068155288572e+01 };
  private static final double[] C = { -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
      -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00 };
  private static final double[] D = { 7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
      3.754408661907416e+00 };

  private IdentityStatistics() {
  }

  /**
   * The trigamma function psi1(x) for x &gt; 0. For a Beta(a, b) variable X, Var[ln X] = psi1(a) - psi1(a + b), which is
   * how the model turns a count-based estimate into the variance of its log. Recurrence up to x &gt;= 10, then the asymptotic
   * series, accurate to about 1e-13.
   */
  static double trigamma(double x) {
    double result = 0;
    while (x < 10) {
      result += 1 / (x * x);
      x += 1;
    }
    final double inv = 1 / x;
    final double inv2 = inv * inv;
    return result + inv + inv2 / 2 + inv * inv2 * (1 / 6.0 - inv2 * (1 / 30.0 - inv2 * (1 / 42.0 - inv2 * (1 / 30.0 - inv2 * 5 / 66.0))));
  }

  /** The standard normal quantile (inverse CDF) by Acklam's rational approximation, relative error below 1.2e-9. */
  static double normalQuantile(final double p) {
    if (p <= 0 || p >= 1)
      throw new IllegalArgumentException("probability must be in (0, 1), got " + p);
    final double low = 0.02425;
    if (p < low) {
      final double q = Math.sqrt(-2 * Math.log(p));
      return (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
    }
    if (p > 1 - low)
      return -normalQuantile(1 - p);
    final double q = p - 0.5;
    final double r = q * q;
    return (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q / (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r
        + B[4]) * r + 1);
  }

  /** The logistic function, without overflow for large |x|. */
  static double sigmoid(final double x) {
    if (x >= 0)
      return 1 / (1 + Math.exp(-x));
    final double e = Math.exp(x);
    return e / (1 + e);
  }

  static double logit(final double p) {
    return Math.log(p / (1 - p));
  }

  /** Variance of a Beta(a, b) variable. */
  static double betaVariance(final double a, final double b) {
    final double s = a + b;
    return a * b / (s * s * (s + 1));
  }
}
