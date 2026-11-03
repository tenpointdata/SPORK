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
package com.arcadedb.spork.keys;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.similarity.Stylometry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the golden vectors written by {@code spork-keys/golden/generate-golden.ts} from Recon's own TypeScript. Every
 * {@code recon} value must come out of {@link ReconNormalizers} (or {@link Stylometry}, {@link UpstreamRecords}) byte for
 * byte; every {@code spork} value out of {@link SporkKeys} and its companions. Regenerate the files with the script after a
 * Recon change, never by hand.
 */
class GoldenVectorsTest {

  @Test
  void phonesMatchEveryReconUseSite() {
    final JSONArray cases = cases("phones.json");
    assertThat(cases.length()).isGreaterThan(200);
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String raw = c.getString("input");
      final JSONObject place = c.getJSONObject("place", null);
      final String city = place == null ? null : place.getString("city");
      final String state = place == null ? null : place.getString("state");
      final JSONObject recon = c.getJSONObject("recon");
      final String at = "phone '" + raw + "' at " + city + ", " + state;

      assertThat(ReconNormalizers.engineCanonPhone(raw, city, state)).as(at).isEqualTo(recon.getString("engineCanonPhone"));
      assertThat(ReconNormalizers.engineAreaCodeOf(raw, city, state)).as(at).isEqualTo(recon.getString("engineAreaCodeOf"));
      assertThat(ReconNormalizers.engineClusterKey(ReconNormalizers.PHONE, raw, city == null ? "" : city, state == null ? "" : state))
          .as(at).isEqualTo(recon.getString("engineClusterKey"));
      assertThat(ReconNormalizers.engineCanonPhone(raw, null, null)).as(at).isEqualTo(recon.getString("engineNormPhone"));
      assertThat(ReconNormalizers.compareNormPhone(raw)).as(at).isEqualTo(recon.getString("compareNormPhone"));
      assertThat(ReconNormalizers.boardNormalizeAnchor(ReconNormalizers.PHONE, raw)).as(at)
          .isEqualTo(recon.getString("boardNormalizeAnchor"));
      assertThat(ReconNormalizers.dossierIdentKey(ReconNormalizers.PHONE, raw)).as(at).isEqualTo(recon.getString("dossierIdentKey"));
      assertThat(ReconNormalizers.feedPhoneKey(raw)).as(at).isEqualTo(recon.getString("feedPhoneKey"));
      assertThat(ReconNormalizers.auditIntakeSubject(List.of(raw))).as(at).isEqualTo(recon.getString("auditIntakeSubject"));
      assertThat(ReconNormalizers.classifySource(raw)).as(at).isEqualTo(recon.getString("classifySource"));

      final JSONObject spork = c.getJSONObject("spork");
      assertThat(SporkKeys.phone(raw, city, state)).as(at).isEqualTo(spork.getString("key", null));
      assertThat(SporkKeys.phoneDigits(raw, city, state)).as(at).isEqualTo(spork.getString("digits"));
      assertThat(SporkKeys.isInferredAreaCode(raw, city, state)).as(at).isEqualTo(spork.getBoolean("inferredAreaCode"));
      // The canonical phone key is the engine's link key (canonPhone with the posting place), prefixed.
      if (!spork.isNull("key"))
        assertThat(spork.getString("key")).as(at).isEqualTo("ph:" + recon.getString("engineCanonPhone"));
    }
  }

  @Test
  void emailsMatchEveryReconUseSite() {
    final JSONArray cases = cases("emails.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String raw = c.getString("input");
      final JSONObject recon = c.getJSONObject("recon");
      assertThat(ReconNormalizers.engineLinkKey(raw)).as(raw).isEqualTo(recon.getString("engineLinkKey"));
      assertThat(ReconNormalizers.engineClusterKey(ReconNormalizers.EMAIL, raw, "", "")).as(raw).isEqualTo(recon.getString("engineClusterKey"));
      assertThat(ReconNormalizers.boardNormalizeAnchor(ReconNormalizers.EMAIL, raw)).as(raw).isEqualTo(recon.getString("boardNormalizeAnchor"));
      assertThat(ReconNormalizers.dossierIdentKey(ReconNormalizers.EMAIL, raw)).as(raw).isEqualTo(recon.getString("dossierIdentKey"));
      assertThat(ReconNormalizers.auditIntakeSubject(List.of(raw))).as(raw).isEqualTo(recon.getString("auditIntakeSubject"));
      assertThat(ReconNormalizers.classifySource(raw)).as(raw).isEqualTo(recon.getString("classifySource"));
      assertThat(SporkKeys.email(raw)).as(raw).isEqualTo(c.getJSONObject("spork").getString("key", null));
    }
  }

  @Test
  void handlesMatchEveryReconUseSite() {
    final JSONArray cases = cases("handles.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String platform = c.getString("platform", null);
      final String handle = c.getString("handle");
      final JSONObject recon = c.getJSONObject("recon");
      final String at = platform + ":" + handle;
      // Recon formats a missing platform as the string "undefined"
      assertThat(ReconNormalizers.engineSocialLinkKey(platform == null ? "undefined" : platform, handle)).as(at)
          .isEqualTo(recon.getString("engineLinkKey"));
      assertThat(ReconNormalizers.boardNormalizeAnchor(ReconNormalizers.SOCIAL, handle)).as(at)
          .isEqualTo(recon.getString("boardNormalizeAnchorHandle"));
      assertThat(ReconNormalizers.dossierIdentKey(ReconNormalizers.SOCIAL, (platform == null ? "undefined" : platform) + ":" + handle))
          .as(at).isEqualTo(recon.getString("dossierIdentKey"));
      assertThat(SporkKeys.handle(platform, handle)).as(at).isEqualTo(c.getJSONObject("spork").getString("key", null));
    }
  }

  @Test
  void businessNamesMatchEveryReconUseSite() {
    final JSONArray cases = cases("businesses.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String raw = c.getString("input");
      final JSONObject recon = c.getJSONObject("recon");
      assertThat(ReconNormalizers.engineLinkKey(raw)).as(raw).isEqualTo(recon.getString("engineLinkKey"));
      assertThat(ReconNormalizers.engineClusterKey(ReconNormalizers.BUSINESS_NAME, raw, "", "")).as(raw)
          .isEqualTo(recon.getString("engineClusterKey"));
      assertThat(ReconNormalizers.boardNormalizeAnchor(ReconNormalizers.BUSINESS_NAME, raw)).as(raw)
          .isEqualTo(recon.getString("boardNormalizeAnchor"));
      assertThat(ReconNormalizers.dossierIdentKey(ReconNormalizers.BUSINESS_NAME, raw)).as(raw).isEqualTo(recon.getString("dossierIdentKey"));
      assertThat(SporkKeys.business(raw)).as(raw).isEqualTo(c.getJSONObject("spork").getString("key", null));
    }
  }

  @Test
  void imageHashesAreVerbatim() {
    final JSONArray cases = cases("image-hashes.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String raw = c.getString("input");
      final JSONObject recon = c.getJSONObject("recon");
      assertThat(ReconNormalizers.dossierIdentKey(ReconNormalizers.IMAGE_HASH, raw)).as(raw).isEqualTo(recon.getString("dossierIdentKey"));
      assertThat(SporkKeys.imageHash(raw)).as(raw).isEqualTo(c.getJSONObject("spork").getString("key", null));
      // Packed form round-trips to the exact upstream string
      final byte[] packed = SporkKeys.packImageHash(raw);
      if (packed != null) {
        assertThat(packed).hasSize(32);
        assertThat(SporkKeys.unpackImageHash(packed)).isEqualTo(raw);
      }
    }
  }

  @Test
  void namesFollowTheSporkRule() {
    final JSONArray cases = cases("names.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String raw = c.getString("input");
      assertThat(SporkKeys.name(raw)).as(raw).isEqualTo(c.getJSONObject("spork").getString("key", null));
    }
  }

  @Test
  void styleVectorsAndFingerprintsMatchReconIdentityTs() {
    final JSONArray cases = cases("style.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String text = c.getString("input");
      final JSONObject recon = c.getJSONObject("recon");
      final JSONArray expected = recon.getJSONArray("styleVector");
      final double[] actual = Stylometry.reconStyleVector(text);
      assertThat(actual).hasSize(expected.length());
      for (int d = 0; d < actual.length; d++)
        assertThat(Double.doubleToLongBits(actual[d])).as("'" + text + "' dim " + d)
            .isEqualTo(Double.doubleToLongBits(expected.getDouble(d)));
      assertThat(Stylometry.reconFingerprint(text)).as(text).isEqualTo(recon.getString("fingerprint"));
      assertThat(StyleKeys.fingerprint(text)).isEqualTo(recon.getString("fingerprint"));
      final float[] f = StyleKeys.vector(text);
      for (int d = 0; d < f.length; d++)
        assertThat(f[d]).isEqualTo((float) expected.getDouble(d));
    }
  }

  @Test
  void textHashesAreExactByte() {
    final JSONArray cases = cases("text-hashes.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final byte[] hash = TextHash.of(c.getString("title"), c.getString("body"));
      assertThat(HexFormat.of().formatHex(hash)).isEqualTo(c.getJSONObject("spork").getString("textHash"));
    }
  }

  @Test
  void upstreamRidsPackAndFormat() {
    final JSONArray cases = cases("upstream-rids.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final String raw = c.getString("input");
      final String expected = c.getJSONObject("spork").getString("upstreamRid", null);
      final long packed = UpstreamRid.parse(raw);
      if (expected == null)
        assertThat(packed).as(raw).isEqualTo(UpstreamRid.INVALID);
      else {
        assertThat(packed).as(raw).isEqualTo(Long.parseLong(expected));
        assertThat(UpstreamRid.format(packed)).isEqualTo(raw.startsWith("#") ? raw.substring(1) : raw);
      }
    }
  }

  @Test
  void placesResolveCoordinatesLikeReconAndKeyLikeSpork() {
    final JSONArray cases = cases("places.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final JSONObject basic = c.getJSONObject("basic");
      final JSONObject location = c.getJSONObject("location");
      final JSONObject recon = c.getJSONObject("recon");
      final String at = location.toString();

      final double[] latLon = UpstreamRecords.latLon(basic, location);
      assertThat(latLon[0]).as(at).isEqualTo(recon.getDouble("lat"));
      assertThat(latLon[1]).as(at).isEqualTo(recon.getDouble("lon"));
      assertThat(UpstreamRecords.city(location)).as(at).isEqualTo(recon.getString("city"));
      assertThat(UpstreamRecords.state(location)).as(at).isEqualTo(recon.getString("state"));
      assertThat(UpstreamRecords.country(location)).as(at).isEqualTo(recon.getString("country"));

      final JSONObject spork = c.getJSONObject("spork");
      final String key = SporkKeys.place(recon.getString("country"), recon.getString("state"), recon.getString("city"), latLon[0], latLon[1]);
      assertThat(key).as(at).isEqualTo(spork.getString("key"));
      assertThat(SporkKeys.placeId(key)).as(at).isEqualTo(Long.parseLong(spork.getString("placeId")));
      assertThat(SporkKeys.cityNorm(recon.getString("city"))).as(at).isEqualTo(spork.getString("cityNorm"));
      assertThat(SporkKeys.geohash7(latLon[0], latLon[1])).as(at).isEqualTo(spork.getString("geohash7", null));
    }
  }

  @Test
  void upstreamRecordIdentifiersMatchMapRecord() {
    final JSONArray cases = cases("records.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      final JSONObject rec = c.getJSONObject("rec");
      final JSONObject recon = c.getJSONObject("recon");
      assertThat(UpstreamRecords.phones(rec)).isEqualTo(strings(recon.getJSONArray("phones")));
      assertThat(UpstreamRecords.emails(rec)).isEqualTo(strings(recon.getJSONArray("emails")));
      assertThat(UpstreamRecords.businessNames(rec)).isEqualTo(strings(recon.getJSONArray("businessNames")));
      assertThat(UpstreamRecords.imageHashes(rec)).isEqualTo(strings(recon.getJSONArray("imageHashes")));
      final List<String[]> socials = UpstreamRecords.socials(rec);
      final JSONArray expected = recon.getJSONArray("socials");
      assertThat(socials).hasSize(expected.length());
      for (int s = 0; s < socials.size(); s++) {
        assertThat(socials.get(s)[0]).isEqualTo(expected.getJSONObject(s).getString("platform"));
        assertThat(socials.get(s)[1]).isEqualTo(expected.getJSONObject(s).getString("handle"));
      }
    }
  }

  @Test
  void sourceTokensClassifyLikeRecon() {
    final JSONArray cases = cases("source-tokens.json");
    for (int i = 0; i < cases.length(); i++) {
      final JSONObject c = cases.getJSONObject(i);
      assertThat(ReconNormalizers.classifySource(c.getString("input"))).as(c.getString("input"))
          .isEqualTo(c.getJSONObject("recon").getString("classifySource"));
    }
  }

  @Test
  void areaCodeTablesMatchReconGeography() {
    final JSONObject tables = cases("area-codes.json").getJSONObject(0);
    assertThat(ReconGeography.areaCodeToPlace()).isEqualTo(map(tables.getJSONObject("areaCodeToPlace")));
    // JS enumerates integer-like keys in ascending numeric order, so Houston inherits 281, not 713
    assertThat(ReconGeography.placeToArea()).isEqualTo(map(tables.getJSONObject("placeToArea")));
    assertThat(ReconGeography.placeToArea().get("Houston, TX")).isEqualTo("281");
  }

  private static Map<String, String> map(final JSONObject o) {
    final Map<String, String> m = new LinkedHashMap<>();
    for (final String k : o.keySet())
      m.put(k, o.getString(k));
    return m;
  }

  private static List<String> strings(final JSONArray a) {
    final List<String> out = new ArrayList<>(a.length());
    for (int i = 0; i < a.length(); i++)
      out.add(a.getString(i));
    return out;
  }

  static JSONArray cases(final String file) {
    try (final InputStream in = GoldenVectorsTest.class.getResourceAsStream("/golden/" + file)) {
      assertThat(in).as(file).isNotNull();
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("cases");
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
