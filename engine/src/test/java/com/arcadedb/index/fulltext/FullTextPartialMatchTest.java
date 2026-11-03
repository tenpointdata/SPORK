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
package com.arcadedb.index.fulltext;

import com.arcadedb.TestHelper;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The built-in partial-match analyzers through SQL and Cypher: substring (n-gram), prefix (edge n-gram), suffix (reverse),
 * phone numbers and phonetic names, alone and as per-field analyzers of one index.
 */
class FullTextPartialMatchTest extends TestHelper {

  private Set<String> values(final String sql, final String property) {
    final Set<String> result = new HashSet<>();
    try (final ResultSet rs = database.query("sql", sql)) {
      while (rs.hasNext())
        result.add(rs.next().getProperty(property));
    }
    return result;
  }

  private void createProfiles() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Profile");
      database.command("sql", "CREATE PROPERTY Profile.name STRING");
      database.command("sql", "CREATE PROPERTY Profile.handle STRING");
      database.command("sql", "CREATE PROPERTY Profile.phone STRING");
      database.command("sql", """
          CREATE INDEX Profile_search ON Profile (name, handle, phone) FULL_TEXT METADATA {
          "name_analyzer": "phonetic",
          "handle_analyzer": "ngram(3,6)",
          "phone_analyzer": "phone"}""");
      database.command("sql", "INSERT INTO Profile SET name = 'John Smith', handle = 'sexy_kitty_2024', phone = '(702) 555-1234'");
      database.command("sql", "INSERT INTO Profile SET name = 'Maria Garcia', handle = 'xo.maria.xo', phone = '+1 415 555 9876'");
      database.command("sql", "INSERT INTO Profile SET name = 'Jon Smyth', handle = 'kittycat99', phone = '702-555-0000'");
    });
  }

  private Set<String> names(final String query) {
    return values("SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', '" + query + "') = true", "name");
  }

  @Test
  void substringOfAHandle() {
    createProfiles();
    database.transaction(() -> {
      assertThat(names("handle:kitty")).containsExactlyInAnyOrder("John Smith", "Jon Smyth");
      assertThat(names("handle:2024")).containsExactly("John Smith");
      assertThat(names("handle:maria")).containsExactly("Maria Garcia");
      // longer than the largest gram (6): every window must match
      assertThat(names("handle:kitty_2024")).containsExactly("John Smith");
      assertThat(names("handle:kitty_2025")).isEmpty();
      // the direct path behind CONTAINSTEXT analyzes with the same per-field query analyzer
      assertThat(values("SELECT FROM Profile WHERE handle CONTAINSTEXT 'itty'", "name"))
          .containsExactlyInAnyOrder("John Smith", "Jon Smyth");
    });
  }

  @Test
  void phoneFragmentsAndFormats() {
    createProfiles();
    database.transaction(() -> {
      assertThat(names("phone:1234")).containsExactly("John Smith");
      assertThat(names("phone:555-1234")).containsExactly("John Smith");
      assertThat(names("phone:\"(702) 555-1234\"")).containsExactly("John Smith");
      assertThat(names("phone:\"+1 702 555 1234\"")).containsExactly("John Smith");
      assertThat(names("phone:4155559876")).containsExactly("Maria Garcia");
      assertThat(names("phone:9876")).containsExactly("Maria Garcia");
      assertThat(names("phone:7025550000")).containsExactly("Jon Smyth");
      assertThat(values("SELECT FROM Profile WHERE phone CONTAINSTEXT '555 1234'", "name")).containsExactly("John Smith");
    });
  }

  @Test
  void phoneticNames() {
    createProfiles();
    database.transaction(() -> {
      assertThat(names("name:smith")).containsExactlyInAnyOrder("John Smith", "Jon Smyth");
      assertThat(names("name:jon")).containsExactlyInAnyOrder("John Smith", "Jon Smyth");
      assertThat(names("name:garsia")).containsExactly("Maria Garcia");
      assertThat(names("+name:jon +name:smyth")).containsExactlyInAnyOrder("John Smith", "Jon Smyth");
    });
  }

  @Test
  void edgeNGramPrefixes() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Person");
      database.command("sql", "CREATE PROPERTY Person.name STRING");
      database.command("sql", "CREATE INDEX ON Person (name) FULL_TEXT METADATA {\"analyzer\": \"edge_ngram\"}");
      database.command("sql", "INSERT INTO Person SET name = 'Johnson'");
      database.command("sql", "INSERT INTO Person SET name = 'Ajohn Doe'");
      database.command("sql", "INSERT INTO Person SET name = 'Joanna Doe'");
    });
    database.transaction(() -> {
      assertThat(values("SELECT FROM Person WHERE SEARCH_INDEX('Person[name]', 'joh') = true", "name")).containsExactly("Johnson");
      assertThat(values("SELECT FROM Person WHERE SEARCH_INDEX('Person[name]', 'jo') = true", "name"))
          .containsExactlyInAnyOrder("Johnson", "Joanna Doe");
      assertThat(values("SELECT FROM Person WHERE name CONTAINSTEXT 'Johns'", "name")).containsExactly("Johnson");
    });
  }

  @Test
  void suffixSearchOnReversedTokens() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Account");
      database.command("sql", "CREATE PROPERTY Account.handle STRING");
      // no allowLeadingWildcard: a reverse index accepts leading wildcards by itself
      database.command("sql", "CREATE INDEX ON Account (handle) FULL_TEXT METADATA {\"analyzer\": \"reverse\"}");
      database.command("sql", "INSERT INTO Account SET handle = 'sexy_kitty_2024'");
      database.command("sql", "INSERT INTO Account SET handle = 'kitty_2023'");
      database.command("sql", "INSERT INTO Account SET handle = 'xo_maria_2024'");
    });
    database.transaction(() -> {
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '*2024') = true", "handle"))
          .containsExactlyInAnyOrder("sexy_kitty_2024", "xo_maria_2024");
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '*kitty_2024') = true", "handle"))
          .containsExactly("sexy_kitty_2024");
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '*kitty*') = true", "handle"))
          .containsExactlyInAnyOrder("sexy_kitty_2024", "kitty_2023");
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '*_20?3') = true", "handle"))
          .containsExactly("kitty_2023");
      // the reversed copies never answer an ordinary wildcard, fuzzy or regexp query
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', 'kitty*') = true", "handle"))
          .containsExactly("kitty_2023");
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '4202_yttik_yxes~1') = true", "handle")).isEmpty();
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '/.*yttik.*/') = true", "handle")).isEmpty();
    });
  }

  @Test
  void ngramWithBm25AndClassicSimilarity() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Handle");
      database.command("sql", "CREATE PROPERTY Handle.value STRING");
      database.command("sql",
          "CREATE INDEX ON Handle (value) FULL_TEXT METADATA {\"analyzer\": \"ngram\", \"similarity\": \"CLASSIC\"}");
      database.command("sql", "INSERT INTO Handle SET value = 'abc123xyz'");
      database.command("sql", "INSERT INTO Handle SET value = 'zzz123'");
    });
    database.transaction(() -> {
      assertThat(values("SELECT FROM Handle WHERE SEARCH_INDEX('Handle[value]', '123') = true", "value"))
          .containsExactlyInAnyOrder("abc123xyz", "zzz123");
      assertThat(values("SELECT FROM Handle WHERE SEARCH_INDEX('Handle[value]', 'c123x') = true", "value"))
          .containsExactly("abc123xyz");
      assertThat(values("SELECT FROM Handle WHERE value CONTAINSTEXT '23x'", "value")).containsExactly("abc123xyz");
    });
  }

  @Test
  void analyzerParametersSurviveReopen() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Code");
      database.command("sql", "CREATE PROPERTY Code.value STRING");
      database.command("sql", "CREATE INDEX ON Code (value) FULL_TEXT METADATA {\"analyzer\": \"ngram(2,3)\"}");
      database.command("sql", "INSERT INTO Code SET value = 'abcdef'");
    });
    reopenDatabase();
    database.transaction(() -> {
      database.command("sql", "INSERT INTO Code SET value = 'zzcdzz'");
      // 'cd' is a 2-gram: only indexed with the persisted (2,3) parameters
      assertThat(values("SELECT FROM Code WHERE SEARCH_INDEX('Code[value]', 'cd') = true", "value"))
          .containsExactlyInAnyOrder("abcdef", "zzcdzz");
    });
  }

  @Test
  void unknownAnalyzerIsRefusedAtCreation() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Bad");
      database.command("sql", "CREATE PROPERTY Bad.value STRING");
    });
    assertThatThrownBy(() -> database.transaction(() -> database.command("sql",
        "CREATE INDEX ON Bad (value) FULL_TEXT METADATA {\"analyzer\": \"ngram(9,2)\"}"))).hasMessageContaining("ngram(9,2)");
  }

  @Test
  void cypherCreatesAPartialMatchIndex() {
    database.command("opencypher", "CREATE FULLTEXT INDEX handles FOR (n:Seller) ON EACH [n.handle] "
        + "OPTIONS {indexConfig: {`fulltext.analyzer`: 'ngram(3,6)'}}");
    database.transaction(() -> {
      database.command("opencypher", "CREATE (:Seller {handle: 'sexy_kitty_2024'})");
      database.command("opencypher", "CREATE (:Seller {handle: 'maria_xo'})");
    });
    database.transaction(() -> {
      final Set<String> handles = new HashSet<>();
      try (final ResultSet rs = database.query("opencypher",
          "CALL db.index.fulltext.queryNodes('handles', 'kitty') YIELD node RETURN node.handle AS handle")) {
        while (rs.hasNext())
          handles.add(rs.next().getProperty("handle"));
      }
      assertThat(handles).containsExactly("sexy_kitty_2024");
    });
  }

  @Test
  void cypherRefusesAnUnknownAnalyzer() {
    assertThatThrownBy(() -> database.command("opencypher", "CREATE FULLTEXT INDEX bad FOR (n:Seller) ON EACH [n.handle] "
        + "OPTIONS {indexConfig: {`fulltext.analyzer`: 'trigram'}}")).hasMessageContaining("trigram");
  }

  /**
   * A reversed field makes the parser accept a leading wildcard on the whole index; on a field without reversed tokens it must
   * still be refused unless allowLeadingWildcard is set, or it walks every token of that field.
   */
  @Test
  void leadingWildcardOnlyWhereTokensAreReversed() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Account");
      database.command("sql", "CREATE PROPERTY Account.name STRING");
      database.command("sql", "CREATE PROPERTY Account.handle STRING");
      database.command("sql", "CREATE INDEX Account_search ON Account (name, handle) FULL_TEXT METADATA {\"handle_analyzer\": \"reverse\"}");
      database.command("sql", "INSERT INTO Account SET name = 'Anna Johnson', handle = 'kitty_2024'");
      database.command("sql", "CREATE DOCUMENT TYPE Open");
      database.command("sql", "CREATE PROPERTY Open.name STRING");
      database.command("sql", "CREATE PROPERTY Open.handle STRING");
      database.command("sql", "CREATE INDEX Open_search ON Open (name, handle) FULL_TEXT METADATA "
          + "{\"handle_analyzer\": \"reverse\", \"allowLeadingWildcard\": true}");
      database.command("sql", "INSERT INTO Open SET name = 'Anna Johnson', handle = 'kitty_2024'");
    });
    database.transaction(() -> {
      assertThat(values("SELECT FROM Account WHERE SEARCH_INDEX('Account_search', 'handle:*2024') = true", "name"))
          .containsExactly("Anna Johnson");
      assertThatThrownBy(() -> values("SELECT FROM Account WHERE SEARCH_INDEX('Account_search', 'name:*son') = true", "name"))
          .hasMessageContaining("leading wildcard");
      assertThatThrownBy(() -> values("SELECT FROM Account WHERE SEARCH_INDEX('Account_search', '*son') = true", "name"))
          .hasMessageContaining("leading wildcard");
      // allowed by the index setting
      assertThat(values("SELECT FROM Open WHERE SEARCH_INDEX('Open_search', 'name:*son') = true", "name"))
          .containsExactly("Anna Johnson");
    });
  }

  /**
   * Only North American numbers used to be made canonical: a number of a country with a trunk prefix, written nationally on one
   * side and internationally on the other, did not match; neither did a number stored with a plus but without its country code.
   */
  @Test
  void phoneNumbersMatchAcrossNationalAndInternationalForms() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Contact");
      database.command("sql", "CREATE PROPERTY Contact.name STRING");
      database.command("sql", "CREATE PROPERTY Contact.uk STRING");
      database.command("sql", "CREATE PROPERTY Contact.us STRING");
      database.command("sql", "CREATE INDEX ON Contact (uk) FULL_TEXT METADATA {\"analyzer\": \"phone(4,44)\"}");
      database.command("sql", "CREATE INDEX ON Contact (us) FULL_TEXT METADATA {\"analyzer\": \"phone\"}");
      database.command("sql", "INSERT INTO Contact SET name = 'national', uk = '020 7946 0958'");
      database.command("sql", "INSERT INTO Contact SET name = 'international', uk = '+44 20 7946 0958'");
      database.command("sql", "INSERT INTO Contact SET name = 'plus without country code', us = '+7025551234'");
    });
    database.transaction(() -> {
      for (final String query : new String[] { "\"020 7946 0958\"", "\"+44 20 7946 0958\"", "00442079460958", "0958", "79460958" })
        assertThat(values("SELECT FROM Contact WHERE SEARCH_INDEX('Contact[uk]', '" + query + "') = true", "name")).as(query)
            .containsExactlyInAnyOrder("national", "international");
      assertThat(values("SELECT FROM Contact WHERE SEARCH_INDEX('Contact[us]', '17025551234') = true", "name"))
          .containsExactly("plus without country code");
      assertThat(values("SELECT FROM Contact WHERE SEARCH_INDEX('Contact[us]', '\"+1 702 555 1234\"') = true", "name"))
          .containsExactly("plus without country code");
    });
  }

  /**
   * A query word longer than the largest edge n-gram was cut to it, so a fully typed long word matched every word it starts as
   * strongly as itself. It still matches them, and the word itself ranks first.
   */
  @Test
  void edgeNGramRanksTheWholeLongWordFirst() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Term");
      database.command("sql", "CREATE PROPERTY Term.value STRING");
      database.command("sql", "CREATE INDEX ON Term (value) FULL_TEXT METADATA {\"analyzer\": \"edge_ngram\"}");
      // inserted first: the lower RID wins a tie
      database.command("sql", "INSERT INTO Term SET value = 'internationalizing'");
      database.command("sql", "INSERT INTO Term SET value = 'internationalization'");
    });
    database.transaction(() -> {
      assertThat(values("SELECT FROM Term WHERE SEARCH_INDEX('Term[value]', 'internationalization') = true", "value"))
          .containsExactlyInAnyOrder("internationalizing", "internationalization");
      try (final ResultSet rs = database.query("sql",
          "SELECT value, $score AS score FROM Term WHERE SEARCH_INDEX('Term[value]', 'internationalization') = true ORDER BY $score DESC")) {
        assertThat(rs.next().<String>getProperty("value")).isEqualTo("internationalization");
      }
    });
  }

  /**
   * Beider-Morse used to replace each word with its phonetic forms, so a prefix or wildcard clause on the field never matched a
   * spelling. The word is kept next to its forms.
   */
  @Test
  void beiderMorseFieldsMatchBySpellingToo() {
    database.transaction(() -> {
      database.command("sql", "CREATE DOCUMENT TYPE Suspect");
      database.command("sql", "CREATE PROPERTY Suspect.name STRING");
      database.command("sql", "CREATE INDEX ON Suspect (name) FULL_TEXT METADATA {\"analyzer\": \"phonetic(beider_morse)\"}");
      database.command("sql", "INSERT INTO Suspect SET name = 'Arnold Schwarzenegger'");
    });
    database.transaction(() -> {
      assertThat(values("SELECT FROM Suspect WHERE SEARCH_INDEX('Suspect[name]', 'schwarz*') = true", "name"))
          .containsExactly("Arnold Schwarzenegger");
      assertThat(values("SELECT FROM Suspect WHERE SEARCH_INDEX('Suspect[name]', 'shwartzenegger') = true", "name"))
          .containsExactly("Arnold Schwarzenegger");
    });
  }
}
