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

import com.arcadedb.database.Database;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.opencypher.procedures.ProcedureSchemaTypes;
import com.arcadedb.query.opencypher.procedures.algo.AbstractAlgoProcedure;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.Evidence;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.FrequencyLookup;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.MatchResult;
import com.arcadedb.query.opencypher.procedures.identity.IdentityMatchModel.Sightings;
import com.arcadedb.query.sql.executor.CommandContext;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.WorkGuard;
import com.arcadedb.similarity.SoftSimilarity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What {@code identity.compare} and {@code identity.cohesion} share: the options that configure the
 * {@link IdentityMatchModel}, loading cluster members, and building a {@link ClusterProfile} from the graph.
 * <p>
 * A member is any node (in Recon, a Record). Its identifiers are the nodes it reaches over {@code via} in
 * {@code direction}, and an identifier's kind is its type name (Phone, Email, ...). An identifier's frequency is the
 * {@code frequencyProperty} of the identifier node when named, otherwise its degree over {@code via} in the opposite
 * direction; it is read only for identifiers both sides share.
 * </p>
 */
abstract class AbstractIdentityProcedure extends AbstractAlgoProcedure {
  static final Set<String> MODEL_KEYS = Set.of("via", "direction", "timeProperty", "latProperty", "lonProperty",
      "frequencyProperty", "population", "prior", "kinds", "defaultM", "defaultConcurrent", "defaultHandoff",
      "defaultHalfLifeDays", "mStrength", "redundancy", "maxSpeedKmh", "conflictWindowHours", "minConflictKm", "conflictLR",
      "confidence", "matchThreshold", "nonMatchThreshold", "maxPairs", "evidenceLimit", "evidenceRecords", "model", "styleProperty", "styleRecon", "imageHashProperty", "soft");

  /** How many records one side of an evidence row names when the call does not say. */
  static final int DEFAULT_EVIDENCE_RECORDS = 10;

  /** The keys a registered model's settings may hold: every model option but the output size and the model itself. */
  static final Set<String> MODEL_SETTING_KEYS;

  static {
    final Set<String> keys = new HashSet<>(MODEL_KEYS);
    keys.remove("evidenceLimit");
    keys.remove("evidenceRecords");
    keys.remove("model");
    MODEL_SETTING_KEYS = Set.copyOf(keys);
  }

  private static final Set<String> KIND_KEYS        = Set.of("m", "concurrent", "handoff", "halfLifeDays");
  private static final Set<String> SOFT_SIGNALS     = Set.of("style", "image");
  private static final Set<String> STYLE_SOFT_KEYS  = Set.of("threshold", "m", "falseMatchRate", "concurrent");
  private static final Set<String> IMAGE_SOFT_KEYS  = Set.of("maxDistance", "m", "falseMatchRate", "concurrent");

  /** The parsed options of one call. */
  final class Settings {
    final String[]                   via;
    final Vertex.DIRECTION           direction;
    final String                     timeProperty;
    final String                     latProperty;
    final String                     lonProperty;
    final String                     frequencyProperty;
    final String                     styleProperty;
    final boolean                    styleRecon;
    final String                     imageHashProperty;
    final IdentityMatchModel         model;
    final int                        evidenceLimit;
    /** How many records one side of an evidence row may name; 0 names none. */
    final int                        evidenceRecords;
    final boolean                    populationGiven;
    /** The {@code name@version} of the registered model the settings came from, null without one. */
    final String                     modelVersion;
    private final Database           database;
    private final Map<Integer, Integer> kindByBucket = new HashMap<>();
    private final Map<RID, Long>     frequencies  = new HashMap<>();

    private Settings(final Map<String, Object> config, final CommandContext context, final int defaultEvidenceLimit,
        final String modelVersion) {
      this.modelVersion = modelVersion;
      this.database = context.getDatabase();
      final Map<String, Object> c = config == null ? Map.of() : config;
      final String[] relTypes = extractRelTypes(c.get("via"), context);
      this.via = relTypes == null ? new String[0] : relTypes;
      this.direction = extractDirection(c.get("direction"), Vertex.DIRECTION.OUT);
      this.timeProperty = stringOption(c, "timeProperty");
      this.latProperty = stringOption(c, "latProperty");
      this.lonProperty = stringOption(c, "lonProperty");
      if ((latProperty == null) != (lonProperty == null))
        throw new IllegalArgumentException(getName() + "(): latProperty and lonProperty go together");
      this.frequencyProperty = stringOption(c, "frequencyProperty");
      this.styleProperty = stringOption(c, "styleProperty");
      this.imageHashProperty = stringOption(c, "imageHashProperty");
      final Object recon = c.get("styleRecon");
      if (recon != null && !(recon instanceof Boolean))
        throw new IllegalArgumentException(getName() + "(): styleRecon must be true or false, got " + recon);
      this.styleRecon = Boolean.TRUE.equals(recon);
      this.evidenceLimit = extractInt(numberOption(c, "evidenceLimit", defaultEvidenceLimit), "evidenceLimit", 0);
      this.evidenceRecords = extractInt(numberOption(c, "evidenceRecords", DEFAULT_EVIDENCE_RECORDS), "evidenceRecords", 0);
      this.populationGiven = c.get("population") != null;

      final IdentityMatchModel m = new IdentityMatchModel();
      try {
        if (c.containsKey("prior"))
          m.prior(numberOption(c, "prior", 0).doubleValue());
        if (c.containsKey("population"))
          m.population(numberOption(c, "population", 0).longValue());
        m.defaults(doubleOption(c, "defaultM"), doubleOption(c, "defaultConcurrent"), doubleOption(c, "defaultHandoff"),
            doubleOption(c, "defaultHalfLifeDays"));
        if (c.containsKey("mStrength"))
          m.mStrength(numberOption(c, "mStrength", 0).doubleValue());
        if (c.containsKey("redundancy"))
          m.redundancy(numberOption(c, "redundancy", 0).doubleValue());
        m.travel(doubleOption(c, "maxSpeedKmh"), doubleOption(c, "conflictWindowHours"), doubleOption(c, "minConflictKm"));
        if (c.containsKey("conflictLR"))
          m.conflictLR(numberOption(c, "conflictLR", 0).doubleValue());
        if (c.containsKey("confidence"))
          m.confidence(numberOption(c, "confidence", 0).doubleValue());
        if (c.containsKey("matchThreshold") || c.containsKey("nonMatchThreshold"))
          m.thresholds(numberOption(c, "matchThreshold", 0.9).doubleValue(), numberOption(c, "nonMatchThreshold", 0.1).doubleValue());
        if (c.containsKey("maxPairs"))
          m.maxPairs(extractInt(numberOption(c, "maxPairs", 0), "maxPairs", 0));

        final Map<String, Object> soft = extractConfig(c.get("soft"), "soft", SOFT_SIGNALS);
        if (soft != null)
          for (final Map.Entry<String, Object> e : soft.entrySet()) {
            final boolean style = e.getKey().equals("style");
            final Map<String, Object> sc = extractConfig(e.getValue(), "soft." + e.getKey(), style ? STYLE_SOFT_KEYS : IMAGE_SOFT_KEYS);
            final Map<String, Object> k = sc == null ? Map.of() : sc;
            m.soft(style ? IdentityMatchModel.SoftSignal.STYLE : IdentityMatchModel.SoftSignal.IMAGE,
                doubleOption(k, style ? "threshold" : "maxDistance"), doubleOption(k, "m"), doubleOption(k, "falseMatchRate"),
                doubleOption(k, "concurrent"));
          }

        final Map<String, Object> kinds = extractMap(c.get("kinds"), "kinds");
        if (kinds != null)
          for (final Map.Entry<String, Object> e : kinds.entrySet()) {
            ProcedureSchemaTypes.requireVertexTypes(getName(), database.getSchema(), e.getKey());
            final Map<String, Object> k = extractConfig(e.getValue(), "kinds." + e.getKey(), KIND_KEYS);
            final Map<String, Object> kc = k == null ? Map.of() : k;
            m.kind(e.getKey(), doubleOption(kc, "m"), doubleOption(kc, "concurrent"), doubleOption(kc, "handoff"),
                doubleOption(kc, "halfLifeDays"));
          }
      } catch (final IllegalArgumentException e) {
        if (e.getMessage() != null && e.getMessage().startsWith(getName() + "()"))
          throw e;
        throw new IllegalArgumentException(getName() + "(): " + e.getMessage(), e);
      }
      m.temporal(timeProperty != null);
      this.model = m;
    }

    /** Defaults the population, when the caller gave none, to the number of records of the members' types. */
    void defaultPopulation(final Collection<Vertex> members) {
      final Set<String> types = new TreeSet<>();
      for (final Vertex v : members)
        types.add(v.getTypeName());
      long total = 0;
      for (final String type : types)
        total += database.countType(type, false);
      model.population(total);
    }

    int kindOf(final RID identifier) {
      return kindByBucket.computeIfAbsent(identifier.getBucketId(),
          bucket -> model.kindIndex(database.getSchema().getTypeNameByBucketId(bucket)));
    }

    /** Frequencies, read once per identifier per call. */
    final FrequencyLookup frequencyLookup = this::frequency;

    /** Records a frequency the caller already counted, so the scoring does not read it again. */
    void cacheFrequency(final RID identifier, final long frequency) {
      frequencies.put(identifier, frequency);
    }

    long frequency(final RID identifier) {
      final Long cached = frequencies.get(identifier);
      if (cached != null)
        return cached;
      long frequency = -1;
      try {
        final Record record = database.lookupByRID(identifier, true);
        if (frequencyProperty != null) {
          if (record instanceof Vertex v && v.get(frequencyProperty) instanceof Number n)
            frequency = n.longValue();
        } else if (record instanceof Vertex v)
          frequency = v.countEdges(opposite(direction), via);
      } catch (final RecordNotFoundException e) {
        // A deleted identifier: fall back to what the clusters show
      }
      frequencies.put(identifier, frequency);
      return frequency;
    }
  }

  /**
   * Parses the options. With {@code model}, the registered model's settings are the base and every explicit option
   * overrides them; {@code kinds} and {@code soft} merge entry by entry and key by key, so one explicit kind or signal
   * setting keeps the model's others.
   */
  Settings parseSettings(final Object options, final CommandContext context, final int defaultEvidenceLimit) {
    final Map<String, Object> config = extractConfig(options, "options", allowedKeys());
    final Object model = config == null ? null : config.get("model");
    if (model == null)
      return new Settings(config, context, defaultEvidenceLimit, null);
    if (!(model instanceof String reference) || reference.isBlank())
      throw new IllegalArgumentException(getName() + "(): model must be a model name or 'name@version', got " + model);

    final IdentityModelRegistry.Resolved resolved = IdentityModelRegistry.resolve(context.getDatabase(), reference, getName());
    for (final String key : resolved.settings().keySet())
      if (!MODEL_SETTING_KEYS.contains(key))
        throw new IllegalArgumentException(getName() + "(): model " + resolved.version() + " has unknown settings key '" + key
            + "', expected one of " + new TreeSet<>(MODEL_SETTING_KEYS));

    final Map<String, Object> merged = new HashMap<>(resolved.settings());
    for (final Map.Entry<String, Object> e : config.entrySet()) {
      if (e.getKey().equals("model"))
        continue;
      if ((e.getKey().equals("kinds") || e.getKey().equals("soft")) && e.getValue() instanceof Map<?, ?> explicit
          && merged.get(e.getKey()) instanceof Map<?, ?> base)
        merged.put(e.getKey(), mergeTwoLevels(base, explicit));
      else
        merged.put(e.getKey(), e.getValue());
    }
    return new Settings(merged, context, defaultEvidenceLimit, resolved.version());
  }

  private static Map<String, Object> mergeTwoLevels(final Map<?, ?> base, final Map<?, ?> explicit) {
    final Map<String, Object> kinds = new HashMap<>();
    for (final Map.Entry<?, ?> e : base.entrySet())
      kinds.put(e.getKey().toString(), e.getValue());
    for (final Map.Entry<?, ?> e : explicit.entrySet()) {
      final String kind = e.getKey().toString();
      if (e.getValue() instanceof Map<?, ?> explicitKind && kinds.get(kind) instanceof Map<?, ?> baseKind) {
        final Map<String, Object> merged = new HashMap<>();
        for (final Map.Entry<?, ?> k : baseKind.entrySet())
          merged.put(k.getKey().toString(), k.getValue());
        for (final Map.Entry<?, ?> k : explicitKind.entrySet())
          merged.put(k.getKey().toString(), k.getValue());
        kinds.put(kind, merged);
      } else
        kinds.put(kind, e.getValue());
    }
    return kinds;
  }

  /**
   * One side of a match as the output needs it: the profile that was scored and the members its indices point at, so an
   * evidence row can name the records behind it and the time of each.
   */
  record Side(ClusterProfile profile, List<Vertex> members) {
  }

  /** Two clusters ready to be compared: their profiles and members, and how many members they had in common (set aside). */
  record Pair(ClusterProfile a, ClusterProfile b, int sharedMembers, List<Vertex> membersA, List<Vertex> membersB) {
    Side sideA() {
      return new Side(a, membersA);
    }

    Side sideB() {
      return new Side(b, membersB);
    }
  }

  /**
   * The common front of {@code identity.compare} and {@code identity.features}, so the two read a pair exactly alike: load
   * both clusters, set aside the members in both, default the population, build both profiles.
   */
  Pair preparePair(final Object argA, final Object argB, final Settings settings, final CommandContext context) {
    final Database database = context.getDatabase();
    final List<Vertex> a = extractMembers(argA, "clusterA", database);
    final List<Vertex> b = extractMembers(argB, "clusterB", database);
    if (a.isEmpty())
      throw new IllegalArgumentException(getName() + "(): clusterA holds no existing node");
    if (b.isEmpty())
      throw new IllegalArgumentException(getName() + "(): clusterB holds no existing node");

    final Set<RID> both = overlap(a, b);
    if (!both.isEmpty()) {
      a.removeIf(v -> both.contains(v.getIdentity()));
      b.removeIf(v -> both.contains(v.getIdentity()));
    }
    if (!settings.populationGiven) {
      final List<Vertex> all = new ArrayList<>(a);
      all.addAll(b);
      settings.defaultPopulation(all);
    }
    final WorkGuard guard = newWorkGuard(context);
    return new Pair(buildProfile(a, settings, guard), buildProfile(b, settings, guard), both.size(), a, b);
  }

  /** The option keys of the procedure. */
  abstract Set<String> allowedKeys();

  /**
   * The existing nodes of a cluster argument, in order and without repeats: a list of nodes, RIDs or RID strings
   * ({@code '#12:5'}). Members that no longer exist are skipped, because a saved cluster can go stale.
   */
  List<Vertex> extractMembers(final Object arg, final String paramName, final Database database) {
    if (!(arg instanceof Collection<?> items))
      throw new IllegalArgumentException(getName() + "(): " + paramName + " must be a list of nodes or RIDs");
    final Map<RID, Vertex> members = new LinkedHashMap<>();
    for (final Object item : items) {
      final RID rid = switch (item) {
        case null -> throw new IllegalArgumentException(getName() + "(): " + paramName + " holds a null");
        case Identifiable identifiable -> identifiable.getIdentity();
        case Result result when result.isElement() -> result.getElement().get().getIdentity();
        case String s -> parseRid(s, paramName);
        default -> throw new IllegalArgumentException(
            getName() + "(): " + paramName + " must hold nodes or RIDs, got " + item.getClass().getSimpleName());
      };
      if (members.containsKey(rid))
        continue;
      final Record record;
      try {
        record = item instanceof Vertex v ? v : database.lookupByRID(rid, true);
      } catch (final RecordNotFoundException e) {
        continue;
      }
      if (!(record instanceof Vertex vertex))
        throw new IllegalArgumentException(getName() + "(): " + paramName + " holds " + rid + ", which is not a node");
      members.put(rid, vertex);
    }
    return new ArrayList<>(members.values());
  }

  /** Builds the profile of the members, reading each member's time, place and identifiers once. */
  ClusterProfile buildProfile(final List<Vertex> members, final Settings settings, final WorkGuard guard) {
    final ClusterProfile.Builder builder = new ClusterProfile.Builder();
    final List<RID> ids = new ArrayList<>();
    int iteration = 0;
    for (final Vertex member : members) {
      ids.clear();
      for (final RID id : member.getConnectedVertexRIDs(settings.direction, settings.via)) {
        guard.checkPeriodically(++iteration);
        ids.add(id);
      }
      final RID[] idArray = ids.toArray(new RID[0]);
      final int[] kinds = new int[idArray.length];
      for (int i = 0; i < idArray.length; i++)
        kinds[i] = settings.kindOf(idArray[i]);
      final long time = settings.timeProperty == null ? ClusterProfile.NO_TIME : toEpochMillis(member, settings.timeProperty);
      final double lat = settings.latProperty == null ? Double.NaN : toDegrees(member, settings.latProperty);
      final double lon = settings.lonProperty == null ? Double.NaN : toDegrees(member, settings.lonProperty);
      final float[] style = settings.styleProperty == null ? null : softValue(member, settings.styleProperty,
          v -> SoftSimilarity.styleOf(v, settings.styleRecon));
      final long[][] images = settings.imageHashProperty == null ? null : softValue(member, settings.imageHashProperty,
          SoftSimilarity::hashesOf);
      builder.addMember(time, lat, lon, idArray, kinds, style, images);
      guard.checkPeriodically(++iteration);
    }
    return builder.build();
  }

  /** The common output of a match: everything but the members. */
  static void putMatch(final Map<String, Object> row, final MatchResult r, final Settings settings, final Side a, final Side b) {
    final int evidenceLimit = settings.evidenceLimit;
    row.put("probability", r.probability());
    row.put("lower", r.lower());
    row.put("upper", r.upper());
    row.put("logOdds", r.logOdds());
    row.put("matchWeight", r.matchWeight());
    row.put("matchWeightStdDev", r.stdDev() / Math.log(2));
    row.put("verdict", r.verdict().name());
    final List<Map<String, Object>> evidence = new ArrayList<>(Math.min(evidenceLimit, r.evidence().size()));
    for (final Evidence e : r.evidence()) {
      if (evidence.size() >= evidenceLimit)
        break;
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", e.type().name());
      m.put("kind", e.kind());
      m.put("identifier", e.identifier());
      m.put("countA", e.countA());
      m.put("countB", e.countB());
      if (e.frequency() >= 0)
        m.put("frequency", e.frequency());
      m.put("gapDays", Double.isNaN(e.gapDays()) ? null : e.gapDays());
      m.put("weight", e.weight());
      m.put("stdDev", e.stdDev());
      putSightings(m, "A", e.a(), a, settings.evidenceRecords);
      putSightings(m, "B", e.b(), b, settings.evidenceRecords);
      evidence.add(m);
    }
    row.put("evidence", evidence);
    row.put("modelVersion", settings.modelVersion);
  }

  /**
   * One side of an evidence row: when that side was seen with it ({@code firstA} / {@code lastA}), and which records put
   * it there ({@code recordsA}, a {@code {record, at}} per member, {@code at} null when the member has no time). The list
   * is cut at {@code limit} and left out entirely when the row rests on no single identifier (DISJOINT, CONFLICT, SOFT) or
   * {@code limit} is 0; {@code countA} always reports the true count, so a cut list is visible against it.
   */
  static void putSightings(final Map<String, Object> row, final String side, final Sightings sightings, final Side of,
      final int limit) {
    row.put("first" + side, time(sightings.first()));
    row.put("last" + side, time(sightings.last()));
    final int[] members = sightings.members();
    if (limit == 0 || members.length == 0)
      return;
    final int count = Math.min(limit, members.length);
    final List<Map<String, Object>> records = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final int member = members[i];
      if (member >= of.members().size())
        continue;
      final Map<String, Object> entry = new LinkedHashMap<>(2);
      entry.put("record", of.members().get(member).getIdentity());
      entry.put("at", time(of.profile().memberTime(member)));
      records.add(entry);
    }
    row.put("records" + side, records);
  }

  static Map<String, Object> temporal(final MatchResult r) {
    final Map<String, Object> t = new LinkedHashMap<>();
    t.put("firstA", time(r.firstA()));
    t.put("lastA", time(r.lastA()));
    t.put("firstB", time(r.firstB()));
    t.put("lastB", time(r.lastB()));
    final long gap = IdentityMatchModel.windowGap(r.firstA(), r.lastA(), r.firstB(), r.lastB());
    t.put("gapDays", gap < 0 ? null : gap / 86_400_000.0);
    if (gap == 0)
      t.put("overlapDays", (Math.min(r.lastA(), r.lastB()) - Math.max(r.firstA(), r.firstB())) / 86_400_000.0);
    t.put("comparablePairs", r.comparablePairs());
    t.put("conflicts", r.conflicts());
    t.put("pairsTruncated", r.pairsTruncated());
    return t;
  }

  private static Long time(final long t) {
    return t == ClusterProfile.NO_TIME ? null : t;
  }

  private RID parseRid(final String s, final String paramName) {
    try {
      return new RID(s);
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException(getName() + "(): " + paramName + " holds '" + s + "', which is not a RID", e);
    }
  }

  private long toEpochMillis(final Vertex member, final String property) {
    final Object value = member.get(property);
    return switch (value) {
      case null -> ClusterProfile.NO_TIME;
      case Number n -> n.longValue();
      case Date d -> d.getTime();
      case Instant i -> i.toEpochMilli();
      case LocalDateTime ldt -> ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
      case LocalDate ld -> ld.toEpochDay() * 86_400_000L;
      case ZonedDateTime zdt -> zdt.toInstant().toEpochMilli();
      case OffsetDateTime odt -> odt.toInstant().toEpochMilli();
      default -> throw new IllegalArgumentException(getName() + "(): " + property + " of " + member.getIdentity()
          + " must be a date, a datetime or epoch milliseconds, got " + value.getClass().getSimpleName());
    };
  }

  /** Reads a soft-signal property, naming the member and property when the value cannot be read. */
  private <T> T softValue(final Vertex member, final String property, final Function<Object, T> reader) {
    try {
      return reader.apply(member.get(property));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(getName() + "(): " + property + " of " + member.getIdentity() + ": " + e.getMessage(), e);
    }
  }

  private double toDegrees(final Vertex member, final String property) {
    final Object value = member.get(property);
    if (value == null)
      return Double.NaN;
    if (value instanceof Number n)
      return n.doubleValue();
    throw new IllegalArgumentException(
        getName() + "(): " + property + " of " + member.getIdentity() + " must be a number, got " + value.getClass().getSimpleName());
  }

  String stringOption(final Map<String, Object> config, final String key) {
    final Object value = config.get(key);
    if (value == null)
      return null;
    if (!(value instanceof String s) || s.isBlank())
      throw new IllegalArgumentException(getName() + "(): " + key + " must be a property name");
    return s;
  }

  Number numberOption(final Map<String, Object> config, final String key, final Number absent) {
    final Object value = config.get(key);
    if (value == null)
      return absent;
    if (!(value instanceof Number n))
      throw new IllegalArgumentException(getName() + "(): " + key + " must be a number, got " + value);
    return n;
  }

  private Double doubleOption(final Map<String, Object> config, final String key) {
    final Number n = numberOption(config, key, null);
    return n == null ? null : n.doubleValue();
  }

  static Vertex.DIRECTION opposite(final Vertex.DIRECTION direction) {
    return switch (direction) {
      case OUT -> Vertex.DIRECTION.IN;
      case IN -> Vertex.DIRECTION.OUT;
      case BOTH -> Vertex.DIRECTION.BOTH;
    };
  }

  /** Members of {@code a} also in {@code b}. */
  static Set<RID> overlap(final List<Vertex> a, final List<Vertex> b) {
    final Set<RID> inA = new HashSet<>();
    for (final Vertex v : a)
      inA.add(v.getIdentity());
    final Set<RID> both = new LinkedHashSet<>();
    for (final Vertex v : b)
      if (inA.contains(v.getIdentity()))
        both.add(v.getIdentity());
    return both;
  }
}
