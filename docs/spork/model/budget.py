# SPORK per-node capacity model (rounds 8 and 9: the System 1 index from the beginning, scheduled by gates rather than
# engineering capacity: phased footprints, data deadlines, the H1 gate, the IVF gate threshold, the LSM_VECTOR fallback
# and the encode compute;
# round 7: native record versioning, the _VersionHistory store for Identity and MEMBER_OF;
# round 6: reconciliation with 02-retrieval-speed.md: HASH point-lookup indexes at the
# engine default page sizes, 32 KB v0 Record pages, the compaction policy kept, hot-tier PDQ band indexes; round 5: per-space
# centroid bytes, per-partition IVF scan, S1 codes in backups, decider throughput, measured GraphBatch rates; round 4 added
# the System 1 index and compaction traffic).
# Every node holds a full copy, so every total here is per node.
# Run: python3 docs/spork/model/budget.py
import datetime

GB = 1e9
R0, R3 = 290e6, 900e6
GROWTH = (R3 - R0) / 24          # records per month (A3)
START = datetime.date(2026, 11, 1)  # month 0

# A6 image refs per record, A8 templatized faces per distinct image
S = {'low': dict(img=1.5, fpi=0.25), 'mid': dict(img=3, fpi=0.5), 'high': dict(img=6, fpi=0.8)}

# Fixed per node (0.4): OS+ROC SDK+encoder models 40, Raft log + WAL 120, vector/IVF build scratch 10, ingest staging 20.
# Round 4: the Raft line was 60 (2 x 15 to 25 GB/day of ingest page deltas). LSM full compactions run on the leader and ship
# every rewritten page through the Raft log, so the line now holds 2 x (25 ingest deltas + 27 largest scheduled full
# compaction + 8 incremental compactions) = 120 GB. Round 6: the point-lookup indexes are HASH and never compact, so the
# largest scheduled rewrite is the current FULL_TEXT at 18.5 GB; 27 (the round-4 recdir LSM index) stays as the reserve.
RAFT_INGEST_PER_DAY, RAFT_FULL_COMPACTION, RAFT_INCREMENTAL_PER_DAY = 25, 27, 8
RAFT_LINE = 2 * (RAFT_INGEST_PER_DAY + RAFT_FULL_COMPACTION + RAFT_INCREMENTAL_PER_DAY)   # 120
FIXED = 40 + RAFT_LINE + 10 + 20   # 190
# Backups: 0 steady (written to an off-node mount before W3d, streamed to the object store after).
# Transient slots (never concurrent, one lease in ops): leader admission 180, n4 build 180 (source restore + output +
# archive), leader backup staging (largest archive, printed below), one full index compaction (old file kept until its
# readers finish, at most the largest index) all fit the 0.9 x usable install reserve.
HEAD = 120                       # resync headroom = database size cap (E1 cold install above it)

# Disk ceilings (0.2)
CEIL = 1560                      # 2 TB class, 1,800 usable
CEIL_X1 = 4520                   # + one 3.84 TB in the same VG, 5,500 usable
CEIL_X2 = 7560                   # + two 3.84 TB (or one 7.68 TB), 9,300 usable
ORDER = 0.8 * CEIL               # 1,248

# Bytes per record (0.4)
# Round 2: denormalized city 12 + countryCode 2 + areaCodes[] 4 + display identifier arrays 59 + style label 8,
# x 1.125 slot reserve = 96 B on the Record document (2.1, 2.5).
DENORM = 96
# Round 3: the style copy on Record is the exact float32 style[24] (96 B), not an INT8 code (32 B), so Recon's
# 0.95-threshold fuzzy links and authorship pairs keep contentHash parity: +64 B x 1.125 slot reserve = +72 B.
STYLE_F32_EXTRA = 72
# Round 4: provenance struct and markings on Record (2.1): _prov {sourceId 2, collectedAt 8, assertedBy 2, confidence 1}
# = 13 B, markings bitmask 8 B, importedId index entry amortized 4 B = 25 B x 1.125 slot reserve = 28 B.
ONTOLOGY_EXTRA = 28
REC_BASE = 387 + STYLE_F32_EXTRA + DENORM + ONTOLOGY_EXTRA + 260 + 120 + 410  # record doc, float style, denormalized fields, provenance+markings, local identifier vertices, light edges + chunks, record indexes
REC_PER_IMG = 18 + 56             # full-length hash in record + imageHashes BY ITEM entry
# Round 4: textHash is SHA-256 over the exact title + body bytes with no normalization (A5 is now the exact-match unique
# fraction, measured in P0a). Every txt component scales with it.
UNIQ = 0.5
# Round 3: the FULL_TEXT index is on the stored `text` property (title + body, EXTERNAL LZ4) and index maintenance derives
# removal keys from the stored value, so `text` stays on node in both tiers until E17. There is no separate body property;
# a short inline title (60 B raw) serves search rows without the external page.
TXT_HOT = UNIQ * (60 + 1200 / 2.2 + 0.8 * 1200 + 296 + 40)   # title, text, single-field FTS, style vector (storeVectorsInGraph), textHash = 951
TXT_WARM = UNIQ * (60 + 1200 / 2.2 + 0.8 * 1200 + 40)        # title, text, unpruned FTS, textHash = 803 (raw style dropped: exact float on Record)
WARM_TEXT_OFF_SAVE = UNIQ * (1200 / 2.2) - 12   # valve 4, needs E17 (plugin-keyed FULL_TEXT): text to the object store, 12 B pointer
WARM_PRUNE_SAVE = 0.4 * UNIQ * 0.8 * 1200       # valve: 0.4 x 480
RECDIR = 70                       # upstreamRid -> period directory (40 B document + about 30 B HASH entry: 21 B at 65% load)
# Round 6 (06-stylometry-perceptual-hash.md 3.1, open question 4): PDQ Hamming band keys (multi-index hashing, 8 bands of
# 32 bits, radius 15) on an LSM BY ITEM index cost about 18 B per band per distinct image = 144 B. Corpus-wide that is
# 1.35B x 144 B = 194 GB at mid 3x next to S-PDQ's 54 GB, so the band index exists only on the hot-tier media_<p> (the
# partitions inside the hot window) and on case databases; the wide radius (31) is served by the S-PDQ IVF space (4c).
# Corpus-wide pHash (2 bands, 36 B, 49 GB at 3x) is not stored: PDQ supersedes it.
BAND_PER_ENTRY_B, PDQ_BANDS, PHASH_BANDS = 18, 8, 2
HOT_BAND_B = PDQ_BANDS * BAND_PER_ENTRY_B   # 144 B per hot-tier distinct image
IMG_B, FACE_B = 170, 300          # Image vertex + sha + upstream-hash index; Face vertex + key + envelope-encrypted slot
# Round 7 (native record versioning, 08-native-versioning-design.md): Identity and MEMBER_OF are versioned types. The
# engine keeps one full record image per superseded version in the internal _VersionHistory store {rid, txFrom, txTo,
# out, in, data}, written in the same transaction as the update or delete, indexed on rid (and out, in for edges), paid
# only by versioned types; reads that do not ask for history read nothing extra. Merge, split and undo now delete and
# recreate MEMBER_OF edges (one history entry per deleted edge) instead of keeping ended edges in the live edge lists.
# Live MEMBER_OF: validFrom + validTo (18 B) replaced by the engine stamp _txFrom (9 B); the 70 B line is kept, the 9 B
# stays as reserve. Identity _txFrom (0.05 x 9 B) is inside the 20 B Identity + ResolutionDecision line.
IDENTITIES_PER_RECORD = 0.05      # (A17) one Identity per about 12 shared identifiers
VERSION_CHURN_PER_YEAR = 0.25     # (A17) superseded versions per Identity and per MEMBER_OF edge per year, conservative
HIST_IDENTITY_B = 200             # history entry for an Identity: 113 B image + rid, txFrom, txTo, header 37 = 150 x 1.125 = 171 + 1 rid index entry 30
HIST_MEMBER_OF_B = 220            # history entry for a MEMBER_OF edge: 61 B image + rid, txFrom, txTo, out, in, header 57 = 118 x 1.125 = 132 + 3 index entries x 30


def history_bytes_per_record_year(sc):
    dimg = S[sc]['img'] * 0.5
    shared = 0.235 + 0.25 * dimg                       # MEMBER_OF edges per record = shared identifiers per record
    return (IDENTITIES_PER_RECORD * VERSION_CHURN_PER_YEAR * HIST_IDENTITY_B
            + shared * VERSION_CHURN_PER_YEAR * HIST_MEMBER_OF_B)


def history_gb(R, sc):
    # History accumulates from month 0 over every record then present: record-months = integral of (R0 + GROWTH t) dt.
    mo = month_of(R)
    record_months = R0 * mo + GROWTH * mo * mo / 2
    return record_months / 12 * history_bytes_per_record_year(sc) / GB
TPL = 448                         # ROC template on disk
TATTOO_PER_DIMG, TATTOO_B = 0.02, 512

# System 1 multimodal index (4c), LSM_IVF_VECTOR postings on disk, centroids on heap. Per item: posting entry (code +
# ordinal + RID) + rerank code. S-IMG 57 + 128 (1-bit 256-d scan, 4-bit rerank) on 30% of distinct images (PDQ-cluster
# representatives); S-PDQ 32 B hash + 8 B RID on every distinct image; S-FACE 70 B PQ-64 posting + 512 B int8 rerank on
# representatives (1 per 2.5 faces); S-TEXT 56 + 128 per unique text; S-TATTOO 74 B on 2% of distinct images; S-DOC 2 GB
# fixed (case-scoped). SPANN boundary replication adds 30% of the posting (scan) side. 4-bit face rerank (256 B) is valve 5.
S1_IMG_REP, S1_IMG_POST, S1_IMG_RERANK = 0.3, 57, 128
S1_PDQ = 40
S1_FACE_POST, S1_FACE_INT8, S1_FACE_4BIT = 70, 512, 256
S1_TEXT_POST, S1_TEXT_RERANK = 56, 128
S1_TATTOO = 74
S1_BOUNDARY = 0.3
S1_DOC_GB = 2
S1_ITEMS_PER_POSTING, S1_PDQ_ITEMS_PER_POSTING = 200, 1000
# Round 5: a resident centroid costs what the engine's own location index costs a resident vector (workstreams/vector-scale.md):
# 32 B location + 4 B ordinal = 36 B, plus the centroid's code at the stated precision, plus its share of the degree-16
# centroid graph (16 x 4 B = 64 B). Centroid codes are int8 for every dense space (nprobe selection needs more precision
# than the 1-bit scan codes): 256-d spaces 256 B, S-FACE 512-d 512 B, S-PDQ the 32 B hash. The round-4 constant of 180 B
# held only for 4-bit 256-d codes and never covered S-FACE.
S1_CENTROID_BASE_B, S1_CENTROID_GRAPH_B = 36, 64
S1_CENTROID_B = {'img': S1_CENTROID_BASE_B + 256 + S1_CENTROID_GRAPH_B,    # 356
                 'text': S1_CENTROID_BASE_B + 256 + S1_CENTROID_GRAPH_B,   # 356
                 'tattoo': S1_CENTROID_BASE_B + 256 + S1_CENTROID_GRAPH_B,  # 356
                 'face': S1_CENTROID_BASE_B + 512 + S1_CENTROID_GRAPH_B,   # 612
                 'pdq': S1_CENTROID_BASE_B + 32 + S1_CENTROID_GRAPH_B,     # 132
                 'style': S1_CENTROID_BASE_B + 24 + S1_CENTROID_GRAPH_B}   # 124 (S-STYLE once moved onto the IVF index)
STYLE_RESIDENT_B = 59            # per hot unique text in jvector: 24 B INT8 code + about 35 B location (1.4)
STYLE_CACHE_GB, STYLE_CACHE_HALVED_GB = 0.8, 0.4   # 16 buckets x 0.5M (or 0.25M) x 104 B
VECTOR_SLICE_GB = 8

# RAM (0.7): ROC cap 45 GB = 4 overhead + 1 case/watch gallery + 5 tattoo quarter + 35 face templates
ROC_FACE_GB = 35
TPL_RAM = 512
PER_NODE_MAX = ROC_FACE_GB * GB / TPL_RAM   # after absorbing a lost node's quarter
CLUSTER_CAP = 3 * PER_NODE_MAX


def month_of(R):
    return (R - R0) / GROWTH


def hot_records(R):
    # Newest 18 months, capped at 200M: today 110M; growth adds 25.4M/month,
    # records older than 18 months age out at about 6.1M/month (today's organic rate).
    return min(110e6 + (GROWTH - 6.1e6) * month_of(R), 200e6)


def s1_bytes_per_record(sc, face_4bit=False):
    img = S[sc]['img']; dimg = img * 0.5; fpr = dimg * S[sc]['fpi']; reps = fpr / 2.5
    post = S1_IMG_REP * dimg * S1_IMG_POST + dimg * S1_PDQ + reps * S1_FACE_POST + UNIQ * S1_TEXT_POST + dimg * TATTOO_PER_DIMG * S1_TATTOO
    rerank = S1_IMG_REP * dimg * S1_IMG_RERANK + reps * (S1_FACE_4BIT if face_4bit else S1_FACE_INT8) + UNIQ * S1_TEXT_RERANK
    return post * (1 + S1_BOUNDARY) + rerank


def s1_centroids(R, sc):
    img = S[sc]['img']; dimg = img * 0.5; fpr = dimg * S[sc]['fpi']; reps = fpr / 2.5
    return {'img': R * S1_IMG_REP * dimg / S1_ITEMS_PER_POSTING, 'text': R * UNIQ / S1_ITEMS_PER_POSTING,
            'face': R * reps / S1_ITEMS_PER_POSTING, 'tattoo': R * dimg * TATTOO_PER_DIMG / S1_ITEMS_PER_POSTING,
            'pdq': R * dimg / S1_PDQ_ITEMS_PER_POSTING}


def s1_heap_gb(R, sc):
    c = s1_centroids(R, sc)
    return sum(c[k] * S1_CENTROID_B[k] for k in c) / GB, sum(c.values())


def style_jvector_gb(R):
    return hot_records(R) * UNIQ * STYLE_RESIDENT_B / GB


def style_ivf_gb(R):
    return hot_records(R) * UNIQ / S1_ITEMS_PER_POSTING * S1_CENTROID_B['style'] / GB


def vector_slice_gb(R, sc, cache=STYLE_CACHE_GB, style_on_ivf=False):
    style = style_ivf_gb(R) if style_on_ivf else style_jvector_gb(R)
    return style + cache + s1_heap_gb(R, sc)[0]


def m(R, sc, with_s1=True):
    mo = month_of(R)
    img = S[sc]['img']; dimg = img * 0.5; fpr = dimg * S[sc]['fpi']
    hot = hot_records(R)
    case = 10 + 30 * mo / 24
    rec = R * (REC_BASE + REC_PER_IMG * img) / GB
    th = hot * TXT_HOT / GB
    tw = (R - hot) * TXT_WARM / GB
    shared = 0.235 + 0.25 * dimg
    hist = history_gb(R, sc)                 # round 7: _VersionHistory for Identity and MEMBER_OF, 0 at month 0
    ident = R * (shared * 270 + 42.5 + 20 + fpr / 2.5 * 120) / GB + 2.6 + case + hist
    rdir = R * RECDIR / GB
    band = hot * dimg * HOT_BAND_B / GB      # round 6: PDQ band index on the hot-tier media partitions only
    media = R * (dimg * IMG_B + fpr * FACE_B) / GB + band
    ops = 20 + 40 * mo / 24
    audit = 1 + 20 * mo / 12
    db = rec + th + tw + ident + rdir + media + ops + audit
    gal = R * fpr * TPL / GB
    tat = R * dimg * TATTOO_PER_DIMG * TATTOO_B / GB
    s1 = (R * s1_bytes_per_record(sc) / GB + S1_DOC_GB) if with_s1 else 0.0
    tot = db + gal + tat + s1 + FIXED + HEAD
    face_meta = R * fpr * FACE_B / GB + R * fpr / 2.5 * 120 / GB   # Face rows + FaceCluster vertices
    return dict(nofaces=db - face_meta + FIXED + HEAD, rec=rec, th=th, tw=tw, ident=ident, hist=hist, rdir=rdir, media=media, band=band, ops=ops,
                audit=audit, db=db, gal=gal, tat=tat, s1=s1, tot=tot, faces=R * fpr / 1e6, reps=R * fpr / 2.5 / 1e6,
                hot=hot / 1e6, bpr_rec=REC_BASE + REC_PER_IMG * img)


def date(mo):
    d = START.month - 1 + int(mo)
    return '%d-%02d' % (START.year + d // 12, d % 12 + 1)


def cross(fn, limit, step=0.1, horizon=60):
    for i in range(int(horizon / step) + 1):
        mo = i * step
        if fn(R0 + GROWTH * mo) >= limit:
            return round(mo, 1)
    return None


print('bytes per record: rec base %d (mid %d), txt hot %.0f, txt warm %.0f, fixed %d (Raft line %d)'
      % (REC_BASE, REC_BASE + REC_PER_IMG * 3, TXT_HOT, TXT_WARM, FIXED, RAFT_LINE))
print('per-node footprint (GB); s1 = System 1 index (4c) at full size (every space); round 9: S-PDQ and S-TEXT from Q1, the other spaces from Q2')
for R in (R0, R3):
    for sc in S:
        o = m(R, sc)
        print('%-4s %4dM' % (sc, R / 1e6), ' '.join('%s=%.1f' % (k, o[k]) for k in
              ('rec', 'th', 'tw', 'ident', 'rdir', 'media', 'ops', 'audit', 'db', 'gal', 'tat', 's1', 'tot')),
              '| %%1560=%d %%4520=%d %%7560=%d' % (round(o['tot'] / CEIL * 100), round(o['tot'] / CEIL_X1 * 100),
                                                 round(o['tot'] / CEIL_X2 * 100)),
              '| no-s1 tot=%.1f (%%1560=%d) | faces=%.0fM reps=%.0fM rec B/r=%d' % (
                  m(R, sc, False)['tot'], round(m(R, sc, False)['tot'] / CEIL * 100), o['faces'], o['reps'], o['bpr_rec']))

a, b = m(R0, 'mid'), m(R3, 'mid')
print('PDQ band index (round 6, hot tier only, inside media): mid today %.1f GB (%.0fM hot distinct images x %d B), mid 3x %.1f GB (%.0fM x %d B); '
      'corpus-wide would be PDQ 8 bands %.0f GB or pHash 2 bands %.0f GB at mid 3x (not stored)'
      % (m(R0, 'mid')['band'], hot_records(R0) * 1.5 / 1e6, HOT_BAND_B, m(R3, 'mid')['band'], hot_records(R3) * 1.5 / 1e6, HOT_BAND_B,
         R3 * 1.5 * HOT_BAND_B / GB, R3 * 1.5 * PHASH_BANDS * BAND_PER_ENTRY_B / GB))
hb = history_bytes_per_record_year('mid')
print('version history (round 7, inside identity, versioned types Identity and MEMBER_OF): Identity %.2f x %.2f x %d B = %.1f B + MEMBER_OF %.2f x %.2f x %d B = %.1f B '
      '= %.1f B per record per year at mid; today %.1f GB (month 0), %.1f GB/yr at 290M, %.1f GB/yr at 900M; accumulated month 12 %.1f GB, month 24 (3x) %.1f GB, month 19.1 %.1f GB; '
      'the old design kept ended MEMBER_OF edges in the live edge lists at %.2f x %.2f x 70 B = %.1f B per record per year'
      % (IDENTITIES_PER_RECORD, VERSION_CHURN_PER_YEAR, HIST_IDENTITY_B, IDENTITIES_PER_RECORD * VERSION_CHURN_PER_YEAR * HIST_IDENTITY_B,
         0.235 + 0.25 * 1.5, VERSION_CHURN_PER_YEAR, HIST_MEMBER_OF_B, (0.235 + 0.25 * 1.5) * VERSION_CHURN_PER_YEAR * HIST_MEMBER_OF_B,
         hb, history_gb(R0, 'mid'), R0 * hb / GB, R3 * hb / GB, history_gb(R0 + GROWTH * 12, 'mid'), history_gb(R3, 'mid'), history_gb(R0 + GROWTH * 19.1, 'mid'),
         0.235 + 0.25 * 1.5, VERSION_CHURN_PER_YEAR, (0.235 + 0.25 * 1.5) * VERSION_CHURN_PER_YEAR * 70))
print('mid slope GB/month (24-month average): %.1f with s1, %.1f without' % ((b['tot'] - a['tot']) / 24,
      (m(R3, 'mid', False)['tot'] - m(R0, 'mid', False)['tot']) / 24))
print('hot tier reaches 200M at month %.1f' % ((200e6 - 110e6) / (GROWTH - 6.1e6)))

print('disk triggers on the 2 TB ceiling, without S1 codes (round 8: the S1 backfill waits for H1 like the face backfill): month, date')
for sc in S:
    t = lambda R, sc=sc: m(R, sc, False)['tot']
    o80, c100 = cross(t, ORDER), cross(t, CEIL)
    print(' ', sc, 'with faces: order@1248', o80, date(o80) if o80 is not None else '-', '| ceiling@1560', c100,
          date(c100) if c100 is not None else '-')
    f = lambda R, sc=sc: m(R, sc)['nofaces']
    fo, fc = cross(f, ORDER), cross(f, CEIL)
    print('      face-free (before the W8 backfill): today %.0f GB, order@1248 month %s, ceiling@1560 month %s %s'
          % (m(R0, sc)['nofaces'], fo, fc, date(fc) if fc is not None else '-'))
print('disk triggers with the S1 index after H1 (every space counted from month 0, so the build quarter does not move them): expanded order@3616 / ceiling@4520 / second-drive ceiling@7560')
for sc in S:
    t = lambda R, sc=sc: m(R, sc)['tot']
    x1o, x1c, x2c = cross(t, 0.8 * CEIL_X1), cross(t, CEIL_X1), cross(t, CEIL_X2)
    print(' ', sc, 'order@3616 month', x1o, date(x1o) if x1o is not None else '-', '| ceiling@4520 month', x1c,
          date(x1c) if x1c is not None else '-', '| ceiling@7560 month', x2c)
    t0 = lambda R, sc=sc: m(R, sc, False)['tot']
    print('      without s1: order@3616 month', cross(t0, 0.8 * CEIL_X1), '| ceiling@4520 month', cross(t0, CEIL_X1))

print('S1 index (4c): bytes per record int8 faces: ' + ' '.join('%s %.0f' % (sc, s1_bytes_per_record(sc)) for sc in S)
      + '; 4-bit faces (valve 5): ' + ' '.join('%s %.0f' % (sc, s1_bytes_per_record(sc, True)) for sc in S))
for sc in S:
    for R in (R0, R3):
        h, c = s1_heap_gb(R, sc)
        print('  %s %dM: disk %.0f GB (4-bit faces %.0f GB), centroids %.2fM, heap %.2f GB' % (
            sc, R / 1e6, m(R, sc)['s1'], R * s1_bytes_per_record(sc, True) / GB + S1_DOC_GB, c / 1e6, h))
c3 = s1_centroids(R3, 'mid')
print('  centroid bytes (36 base + int8 code + 64 graph share): ' + ', '.join('%s %d B' % (k, S1_CENTROID_B[k]) for k in ('img', 'text', 'tattoo', 'face', 'pdq', 'style')))
print('  mid 3x centroid heap by space (GB): ' + ', '.join('%s %.2fM x %d = %.2f' % (k, c3[k] / 1e6, S1_CENTROID_B[k], c3[k] * S1_CENTROID_B[k] / GB) for k in ('img', 'text', 'tattoo', 'face', 'pdq'))
      + '; per item %.2f B against 32 to 64 B per item for SPANN and DiskANN' % (s1_heap_gb(R3, 'mid')[0] * GB / (R3 * (S1_IMG_REP * 1.5 + UNIQ + 0.3 + 1.5 * TATTOO_PER_DIMG + 1.5))))
# S1 per-space detail at mid 3x
dimg3 = 1.5; reps3 = 0.3
print('  mid 3x per space (GB): S-IMG %.0f (post %.0f + rerank %.0f), S-PDQ %.0f, S-FACE %.0f (post %.0f + int8 %.0f), S-TEXT %.0f (post %.0f + rerank %.0f), S-TATTOO %.1f, boundary %.0f, S-DOC %d'
      % (R3 * S1_IMG_REP * dimg3 * (S1_IMG_POST + S1_IMG_RERANK) / GB, R3 * S1_IMG_REP * dimg3 * S1_IMG_POST / GB, R3 * S1_IMG_REP * dimg3 * S1_IMG_RERANK / GB,
         R3 * dimg3 * S1_PDQ / GB, R3 * reps3 * (S1_FACE_POST + S1_FACE_INT8) / GB, R3 * reps3 * S1_FACE_POST / GB, R3 * reps3 * S1_FACE_INT8 / GB,
         R3 * UNIQ * (S1_TEXT_POST + S1_TEXT_RERANK) / GB, R3 * UNIQ * S1_TEXT_POST / GB, R3 * UNIQ * S1_TEXT_RERANK / GB,
         R3 * dimg3 * TATTOO_PER_DIMG * S1_TATTOO / GB,
         R3 * S1_BOUNDARY * (S1_IMG_REP * dimg3 * S1_IMG_POST + dimg3 * S1_PDQ + reps3 * S1_FACE_POST + UNIQ * S1_TEXT_POST + dimg3 * TATTOO_PER_DIMG * S1_TATTOO) / GB,
         S1_DOC_GB))
print('  vector residency slice (8 GB): style jvector %.1f + search cache %.1f + S1 centroids %.2f = %.1f GB at mid 3x; today %.1f GB'
      % (style_jvector_gb(R3), STYLE_CACHE_GB, s1_heap_gb(R3, 'mid')[0], vector_slice_gb(R3, 'mid'), vector_slice_gb(R0, 'mid')))
over = cross(lambda R: vector_slice_gb(R, 'mid'), VECTOR_SLICE_GB)
over_half = cross(lambda R: vector_slice_gb(R, 'mid', STYLE_CACHE_HALVED_GB), VECTOR_SLICE_GB)
print('  slice exceeds 8 GB at month %s (%s) with the 0.8 GB cache, month %s (%s) with the cache halved to 0.25M per bucket (%.1f GB)'
      % (over, date(over) if over is not None else '-', over_half, date(over_half) if over_half is not None else '-', STYLE_CACHE_HALVED_GB))
print('  with S-STYLE on the IVF index (%.1fM style centroids x %d B = %.2f GB): slice %.1f GB at mid 3x, %.1f GB at high 3x; margin %.1f GB at mid'
      % (hot_records(R3) * UNIQ / S1_ITEMS_PER_POSTING / 1e6, S1_CENTROID_B['style'], style_ivf_gb(R3),
         vector_slice_gb(R3, 'mid', STYLE_CACHE_HALVED_GB, True), vector_slice_gb(R3, 'high', STYLE_CACHE_HALVED_GB, True),
         VECTOR_SLICE_GB - vector_slice_gb(R3, 'mid', STYLE_CACHE_HALVED_GB, True)))
# IVF scan per probe (round 5): postings live inside the 12 period databases at 3x, one LSM_IVF_VECTOR per space per
# partition sharing the global codebook, so a corpus-wide probe reads nprobe posting pages in every partition.
NPROBE, IO_US, POOL, COMPUTE_MS_PER_PARTITION = 64, 100, 32, 0.2
for R, lbl in ((R0, '300M'), (R3, '900M')):
    parts = max(1, round(R / 75e6))
    reads = parts * NPROBE
    io_ms = reads / POOL * IO_US / 1000
    comp = parts * COMPUTE_MS_PER_PARTITION
    items = S1_ITEMS_PER_POSTING / parts
    print('  IVF scan %s: %d partitions x nprobe %d = %d posting-page reads, %.1f ms I/O on the %d-way pool + %.1f ms compute = %.1f ms; '
          'about %.0f items (%.1f KB) per partition posting, %d postings per 16 KB page' % (
              lbl, parts, NPROBE, reads, io_ms, POOL, comp, io_ms + comp, items, items * S1_IMG_POST / 1024, 16 * 1024 // max(1, int(items * S1_IMG_POST))))
print('  IVF scan with decider partition pruning to the newest 4 partitions at 900M: %.1f ms (the 300M figure)' % (4 * NPROBE / POOL * IO_US / 1000 + 4 * COMPUTE_MS_PER_PARTITION))
print('  per-partition centroid directory (8 B per centroid, in index pages not heap): %.0f MB per partition, %.1f GB over 12 at mid 3x'
      % (s1_heap_gb(R3, 'mid')[1] * 8 / 1e6, s1_heap_gb(R3, 'mid')[1] * 8 * 12 / GB))

print('identity crosses 120 GB (E1 needed):')
for sc in S:
    mo = cross(lambda R, sc=sc: m(R, sc)['ident'], 120)
    print(' ', sc, mo, date(mo) if mo is not None else '-')

print('ROC: per node %.1fM templates after failover, failover-safe cluster cap %.0fM' % (PER_NODE_MAX / 1e6, CLUSTER_CAP / 1e6))
for sc in S:
    mo = cross(lambda R, sc=sc: m(R, sc)['reps'] * 1e6, CLUSTER_CAP)
    R = R0 + GROWTH * mo if mo is not None else None
    print(' ', sc, 'reps > cap at month', mo, date(mo) if mo is not None else '-',
          'R=%.0fM' % (R / 1e6) if R else '', 'reps today %.0fM, 3x %.0fM' % (m(R0, sc)['reps'], m(R3, sc)['reps']))
print('  steady state per node at cap: %.1fM; H2 (150 GB ROC cap, 140 GB faces) cluster cap %.0fM'
      % (CLUSTER_CAP / 4 / 1e6, 3 * 140e9 / TPL_RAM / 1e6))

print('valves at mid (GB saved at month m); valves 1 to 3 exist only from Q2 (WS7 aging rewrite, W8 gallery), valve 5 with 4c')
for mo in (6, 12, 24):
    R = R0 + GROWTH * mo; o = m(R, 'mid'); hot = hot_records(R)
    prune = (R - hot) * WARM_PRUNE_SAVE / GB
    nonrep = o['gal'] * 0.6
    hotcap = min(50e6, max(hot - 150e6, 0)) * (TXT_HOT - TXT_WARM) / GB
    face4 = R * (s1_bytes_per_record('mid') - s1_bytes_per_record('mid', True)) / GB
    print('  month', mo, 'tot %.0f' % o['tot'], 'prune %.0f' % prune, 'nonrep %.0f' % nonrep, 'hotcap %.0f' % hotcap,
          'face4bit %.0f' % face4, 'sum %.0f' % (prune + nonrep + hotcap + face4), 'tot-after %.0f' % (o['tot'] - prune - nonrep - hotcap - face4))

print('database bytes only (no gallery, s1, fixed, headroom):',
      ' '.join('%s today %.0f 3x %.0f' % (sc, m(R0, sc)['db'], m(R3, sc)['db']) for sc in S))
print('mid 3x: hot rec %.0f, warm rec %.0f' % (200e6 * m(R3, 'mid')['bpr_rec'] / GB, 700e6 * m(R3, 'mid')['bpr_rec'] / GB))
print('rec partition capacity at 120 GB (mid): %.0fM records; periods today %.1f, 3x %.1f'
      % (120e9 / m(R0, 'mid')['bpr_rec'] / 1e6, R0 / (120e9 / m(R0, 'mid')['bpr_rec']), R3 / (120e9 / m(R0, 'mid')['bpr_rec'])))
print('A5 sensitivity: each +0.1 of exact-match unique fraction adds %.0f GB at mid 3x (%.1f%% of total)'
      % ((200e6 * TXT_HOT + 700e6 * TXT_WARM) / UNIQ * 0.1 / GB, (200e6 * TXT_HOT + 700e6 * TXT_WARM) / UNIQ * 0.1 / GB / m(R3, 'mid')['tot'] * 100))

print('database bytes before faces (initial load): mid today %.0f, low today %.0f, high today %.0f'
      % (m(R0, 'mid')['nofaces'] - FIXED - HEAD, m(R0, 'low')['nofaces'] - FIXED - HEAD, m(R0, 'high')['nofaces'] - FIXED - HEAD))
print('largest backup archive at 2:1 (identity): mid today %.0f, mid 3x %.0f; one full generation mid today %.0f, 3x %.0f'
      % (m(R0, 'mid')['ident'] / 2, m(R3, 'mid')['ident'] / 2, m(R0, 'mid')['db'] / 2, m(R3, 'mid')['db'] / 2))
# Backup mount (round 4, Q13): 7 dailies (+1 being written) only for identity, ops, audit, recdir and the current
# period group (rec 120 + txt 73 hot + media 37 at 2:1); sealed databases once at seal plus after deletion batches
# (1 copy + 1 being rewritten, the largest 60 GB).
# Round 5: the S1 codes (4c) live inside media_<p> and txt_<p>, so they are in every archive: the current period group
# carries about 75M x 453 B = 34 GB of codes (17 GB at 2:1) and every sealed partition its share; sealed partitions are
# re-archived when the S1 backfill completes on them (third re-archive trigger, 0.4).
for R, lbl in ((R0, 'today'), (R3, '3x')):
    o = m(R, 'mid')
    s1_current = 75e6 * s1_bytes_per_record('mid') / GB
    daily0 = (o['ident'] + o['ops'] + o['audit'] + o['rdir'] + 120 + 73 + 37) / 2
    sealed0 = max(o['db'] - (o['ident'] + o['ops'] + o['audit'] + o['rdir']) - (120 + 73 + 37), 0) / 2
    daily = daily0 + s1_current / 2
    sealed = sealed0 + max(o['s1'] - S1_DOC_GB - s1_current, 0) / 2
    print('backup mount mid %s: without S1 daily set %.0f GB x 8 = %.0f + sealed once %.0f + 60 rewrite = %.0f GB; with S1 codes daily %.0f (+%.0f) x 8 = %.0f + sealed %.0f (+%.0f) + 60 = %.0f GB (7 full generations would be %.0f GB)'
          % (lbl, daily0, 8 * daily0, sealed0, 8 * daily0 + sealed0 + 60, daily, s1_current / 2, 8 * daily, sealed, sealed - sealed0, 8 * daily + sealed + 60, 7 * (o['db'] + o['s1']) / 2))
    print('  S1 codes per sealed partition at mid: media_<p> %.0f GB, txt_<p> %.1f GB' % (
        75e6 * (s1_bytes_per_record('mid') - UNIQ * (S1_TEXT_POST * (1 + S1_BOUNDARY) + S1_TEXT_RERANK)) / GB,
        75e6 * UNIQ * (S1_TEXT_POST * (1 + S1_BOUNDARY) + S1_TEXT_RERANK) / GB))
# Face backfill (6.1): DETECT 0.1 core-s per distinct image; CLUSTER blocking = 2,000 x 0.23 us per face;
# case-gallery check = faces x G x 0.23 us.
dimg0 = R0 * 3 * 0.5; faces0 = m(R0, 'mid')['faces'] * 1e6
det = dimg0 * 0.1; blk = faces0 * 2000 * 0.23e-6
print('face backfill mid today: distinct images %.0fM, DETECT %.1fM core-s = %.0f core-days (%.1f days on 16 cores, %.1f on 64)'
      % (dimg0 / 1e6, det / 1e6, det / 86400, det / 86400 / 16, det / 86400 / 64))
print('  CLUSTER blocking only %.2fM core-s = %.1f core-days' % (blk / 1e6, blk / 86400))
for G in (20e3, 200e3, 2e6):
    c = faces0 * G * 0.23e-6
    print('  case-gallery check at G=%.0fK: %.1fM core-s = %.0f core-days' % (G / 1e3, c / 1e6, c / 86400))
print('ROC interactive at cap: %.2f s with 16 free cores; %.2f s if n4 workers take 4.75 cores; after failover %.2f s / %.2f s'
      % (CLUSTER_CAP / 4 * 0.23e-6 / 16, CLUSTER_CAP / 4 * 0.23e-6 / 11.25, PER_NODE_MAX * 0.23e-6 / 16, PER_NODE_MAX * 0.23e-6 / 11.25))
# S1 encode (4c): Tier A (YuNet + AuraFace + PE/SigLIP + text presence) 3.5 ms/img on one H100; CPU int8 ONNX about
# 2 img/s per core for the scene model; PP-OCRv5 CPU 1.75 s per text-bearing image (35% of images), GPU batched 100/s.
for R, lbl, perday in ((R0, 'today', 300e3), (R3, '3x pace', 1.25e6)):
    print('S1 encode %s: %.2fM distinct images/day: GPU tier A %.1f h + OCR %.1f h on one H100-class card; CPU 16 cores cluster-wide: tier A %.1f h, OCR %.1f h'
          % (lbl, perday / 1e6, perday * 3.5e-3 / 3600, perday * 0.35 / 100 / 3600, perday / 32 / 3600, perday * 0.35 * 1.75 / 16 / 3600))
print('S1 encode backfill mid today: %.0fM distinct images x 3.5 ms = %.0f GPU-h tier A + OCR %.0fM x 10 ms = %.0f GPU-h + text %.0fM x 1.2 ms = %.0f GPU-h'
      % (dimg0 / 1e6, dimg0 * 3.5e-3 / 3600, dimg0 * 0.35 / 1e6, dimg0 * 0.35 * 10e-3 / 3600, R0 * UNIQ / 1e6, R0 * UNIQ * 1.2e-3 / 3600))
# Decider throughput (4d.3, round 5). The decider is one non-autoregressive pass, so it is prefill-bound. Planning figure
# for a 27B dense model in FP16 on one 80 GB H100-class card, batch 32 states: 2 x 27e9 FLOP per token against about
# 990 TFLOPS dense FP16 is 18K tokens/s at 100% utilization; plan at 45% (8K tokens/s), measured in WS14 before any rate is promised.
DECIDER_TOK_PER_STATE, DECIDER_PREFILL_TOK_S = 1500, 8000
DECIDER_THEORETICAL_TOK_S = 990e12 / (2 * 27e9)   # about 18K tokens/s
for perday, lbl in ((200e3, 'today'), (835e3, '3x pace')):
    tok = perday * DECIDER_TOK_PER_STATE
    for tps in (DECIDER_PREFILL_TOK_S, 2000):
        h = tok / tps / 3600
        print('decider ingest scoring %s at %d tokens/s: %.2fB tokens/day = %.0f GPU-hours/day = %.1f cards continuously' % (lbl, tps, tok / 1e9, h, h / 24))
print('  one card at %d tokens/s scores %.0fK states/day at 100%% duty; the newest 300K/day is %.0f%% duty today and %.0f%% of the 3x stream'
      % (DECIDER_PREFILL_TOK_S, DECIDER_PREFILL_TOK_S * 86400 / DECIDER_TOK_PER_STATE / 1e3, 300e3 * DECIDER_TOK_PER_STATE / (DECIDER_PREFILL_TOK_S * 86400) * 100, 300e3 / 835e3 * 100))
print('  checkpoint: FP16 about 49 GiB (80 GB card only); int8 about 25 GiB fits an L40S 48 GB; int4 about 14 GiB fits an L4 24 GB (accuracy cost measured against the 85.7% panel)')
# GraphBatch load (6.1, round 5): measured in the read-bench worktree with WAL on, 30.5M elements (5M records, 6M identifiers,
# 19.5M edges) in 115 s; index builds 622 s of which the vector graph 413 s.
GB_ELEM_PER_S = 30.5e6 / 115
elems0 = R0 / 5e6 * 30.5e6
idx_novec_s_per_5m, idx_vec_s_per_5m = 622 - 413, 413
hot_vecs0 = hot_records(R0) * UNIQ
print('GraphBatch (WAL on): %.0fK elements/s measured; initial load about %.1fB elements = %.1f h; non-vector index builds %.1f h (%.0f s per 5M records, linear); '
      'style graphs for %.0fM hot vectors %.1f to %.1f h (413 s per 5M, 1 to 2x for worse-than-linear); total about %.0f to %.0f h on n4'
      % (GB_ELEM_PER_S / 1e3, elems0 / 1e9, elems0 / GB_ELEM_PER_S / 3600, R0 / 5e6 * idx_novec_s_per_5m / 3600, idx_novec_s_per_5m,
         hot_vecs0 / 1e6, hot_vecs0 / 5e6 * idx_vec_s_per_5m / 3600, 2 * hot_vecs0 / 5e6 * idx_vec_s_per_5m / 3600,
         elems0 / GB_ELEM_PER_S / 3600 + R0 / 5e6 * idx_novec_s_per_5m / 3600 + hot_vecs0 / 5e6 * idx_vec_s_per_5m / 3600,
         elems0 / GB_ELEM_PER_S / 3600 + R0 / 5e6 * idx_novec_s_per_5m / 3600 + 2 * hot_vecs0 / 5e6 * idx_vec_s_per_5m / 3600))
print('  WAL off retains about 8 KB of heap per record: a 12 GB build slice fills at %.1fM records, so every build runs withWAL(true)' % (12e9 / 8192 / 1e6))
# n4 components job (2.6): streaming union-find instead of a GraphAnalyticalView CSR
V = 640e6
print('n4 GAV CSR at 3x (rejected): steady %.1f GB, build transients +%.1f GB' % ((V*8 + 2*2*V*4 + 450e6*2*4 + V*8 + V*4) / GB, 4 * V * 4 / GB))
print('n4 streaming union-find at 3x: sorted RID long[] %.2f + parent int[] %.2f + size int[] %.2f = %.1f GB'
      % (V*8/GB, V*4/GB, V*4/GB, V*16/GB))
# Registry-only GAV (4b): CO_OCCURS + ACCEPTED_LINK + MEMBER_OF over 549M shared identifiers at 3x, about 450M edges
Vr, Er = 549e6, 450e6
print('registry-only GAV at 3x (4b, n4): positions %.1f + offsets %.1f + neighbors %.1f + degree %.1f = %.1f GB'
      % (Vr * 8 / GB, 2 * 2 * Vr * 4 / GB, Er * 2 * 4 / GB, Vr * 4 / GB, (Vr * 8 + 2 * 2 * Vr * 4 + Er * 2 * 4 + Vr * 4) / GB))


def valves(R):
    hot = hot_records(R)
    return (R - hot) * WARM_PRUNE_SAVE / GB + m(R, 'mid')['gal'] * 0.6 + min(50e6, max(hot - 150e6, 0)) * (TXT_HOT - TXT_WARM) / GB


print('valve 4 (E17, warm text off node) at mid: today %.0f GB, 3x %.0f GB'
      % ((R0 - hot_records(R0)) * WARM_TEXT_OFF_SAVE / GB, (R3 - hot_records(R3)) * WARM_TEXT_OFF_SAVE / GB))
cap = 120e9 / m(R0, 'mid')['bpr_rec']
print('mid period group at %.0fM records: rec 120, txt warm %.0f (hot %.0f), media %.0f; n4 aging slot = hot %.0f + warm %.0f + archive %.0f = %.0f GB'
      % (cap / 1e6, cap * TXT_WARM / GB, cap * TXT_HOT / GB, cap * 480 / GB, cap * TXT_HOT / GB, cap * TXT_WARM / GB, cap * TXT_WARM / 2 / GB,
         cap * TXT_HOT / GB + cap * TXT_WARM / GB + cap * TXT_WARM / 2 / GB))

# Cold read volume (2.5, 4; round 6 reconciled with 02-retrieval-speed.md C8 and C9). Stock pages: LSM index 256 KB,
# bucket 64 KB. SPORK schema v1: HASH point-lookup indexes at the engine defaults (4 KB for LONG and UUID keys: record_id,
# upstreamRid, recdir upstreamRid, placeId; 16 KB for STRING and BINARY keys: identifier key, textHash, imageHashes BY ITEM),
# Record and TextBody buckets 32 KB (v0 format: a fixed 8,202 B header and 2,048-slot table), recdir 16 KB, TextBody external
# pages 16 KB. After M20 (256-slot bucket format) Record and TextBody move to 16 KB on new partitions.
KB = 1024                        # MB figures below use the documents' shorthand: 1 MB = 1,000 KB
def io(reads, bytes_moved, lat_us=125, par=32, bw=(5e9, 6.5e9)):
    return -(-reads // par) * lat_us / 1000, bytes_moved / bw[1] * 1000, bytes_moved / bw[0] * 1000
HASH_LONG_KB, HASH_STR_KB, RECDIR_DOC_KB = 4, 16, 16
for label, rec_pg, txt_pg in (('stock 64 KB buckets, 256 KB LSM', 64, 64), ('v1 (32 KB v0 Record)', 32, 32), ('v1 after M20 (16 KB v2)', 16, 16)):
    if label.startswith('stock'):
        per_rid = (256 + 64 + 256 + 64) * KB; per_rec_id = (256 + 64) * KB; per_row = (64 + 256 + 64) * KB
    else:
        per_rid = (HASH_LONG_KB + RECDIR_DOC_KB + HASH_LONG_KB + rec_pg) * KB; per_rec_id = (HASH_LONG_KB + rec_pg) * KB
        per_row = (rec_pg + HASH_STR_KB + txt_pg) * KB
    h = io(8000, 2000 * per_rid); hi = io(4000, 2000 * per_rec_id); d = io(15000, 7500 * per_rec_id); r = io(1500, 500 * per_row)
    print('%-34s hydrate 2,000 upstream RIDs: %d KB each, %.0f MB, latency %.0f ms, bandwidth %.0f to %.0f ms; page cache share n1-n3 %.1f%%, n4 %.1f%%'
          % (label, per_rid // KB, 2000 * per_rid / (1000 * KB), h[0], h[1], h[2], 2000 * per_rid / 23e9 * 100, 2000 * per_rid / 4e9 * 100))
    print('%-34s   by record_id: %d KB each, %.0f MB, latency %.0f ms, bandwidth %.0f to %.0f ms; dossier 7,500 record_ids %.0f MB, latency %.0f, bandwidth %.0f to %.0f ms'
          % ('', per_rec_id // KB, 2000 * per_rec_id / (1000 * KB), hi[0], hi[1], hi[2], 7500 * per_rec_id / (1000 * KB), d[0], d[1], d[2]))
    print('%-34s   search 500 rows (Record + textHash HASH + TextBody): %d KB each, %.0f MB, latency %.0f ms, bandwidth %.0f to %.0f ms'
          % ('', per_row // KB, 500 * per_row / (1000 * KB), r[0], r[1], r[2]))
# Bucket page density (round 6): v0 pages carry an 8 B page header + 2,048 x 4 B slot table = 8,202 B whatever the size
# (02-retrieval-speed.md 0.2); the M20 v2 format has a 256-slot table (1,026 B). Record row 609 B (02-retrieval-speed.md 0.2).
ROW_B, PART = 609, 77e6
for label, pg, hdr in (('16 KB v0', 16, 8202), ('32 KB v0', 32, 8202), ('64 KB v0', 64, 8202), ('16 KB v2 (M20)', 16, 1026)):
    rows = (pg * KB - hdr) // ROW_B
    gb = PART / rows * pg * KB / GB
    print('Record bucket %-15s %3d rows per page, %.1f%% record space, %.0f GB per 77M partition, %.0f B of page per 637 B record line (+%.0f B)'
          % (label, rows, (pg * KB - hdr) / (pg * KB) * 100, gb, pg * KB / rows, pg * KB / rows - 637))
# HASH resident set (round 6): directory of 4 B per bucket page, 2^ceil(log2(pages)) entries; a 4 KB page holds about 125
# LONG entries (8 B key + 10 B RID + 3 B slot at 65% load), a 16 KB page about 250 STRING keys.
import math
def hash_dir_mb(keys, per_page):
    pages = keys / per_page
    return 2 ** math.ceil(math.log2(pages)) * 4 / 1e6, pages / 1e6
for R, lbl in ((R0, 'today'), (R3, '3x')):
    rd = hash_dir_mb(R, 125); ident = hash_dir_mb(R * 0.61, 250)
    part = hash_dir_mb(77e6, 125)[0] * 2 + hash_dir_mb(77e6, 250)[0] + hash_dir_mb(115e6, 250)[0] + hash_dir_mb(46e6, 250)[0]
    parts = max(1, round(R / 75e6))
    print('HASH directories %s: recdir %.0f MB (%.1fM pages of 4 KB), identity keys %.0f MB, per partition %.0f MB x %d; LSM composite tops 27 x 3.4 MB x %d = %.0f MB; total about %.2f GB'
          % (lbl, rd[0], rd[1], ident[0], part, parts, parts, 27 * 3.4 * parts, (rd[0] + ident[0] + part * parts + 27 * 3.4 * parts) / 1e3))
# Counterfactual (rounds 3 and 4, rejected in round 6): LSM point-lookup indexes. A point lookup visits every compacted
# series (1,024 data pages each): last data page + one 8 KB bloom page per series, 1 + 2S + 2 cached touches, all expected
# resident. Bloom at 0.01 FPR is about 1.2 B per key.
for label, pg in (('16 KB', 16 * KB), ('64 KB', 64 * KB)):
    for R, lbl in ((R0, 'today'), (R3, '3x')):
        rd = R * 30; part = 77e6 * 30
        print('LSM counterfactual recdir upstreamRid %s pages, %s: %.1f GB, %.0f series per probe (%d cached touches); partition upstreamRid %.0f series; bloom resident recdir %.2f GB, all point-lookup %.2f GB; walk CPU per probe about %.2f ms at 0.3 us per series'
              % (label, lbl, rd / GB, rd / (1024 * pg), 1 + 2 * rd / (1024 * pg) + 2, part / (1024 * pg), R * 1.2 / GB, (R * 1.2 * 3 + R * UNIQ * 1.2) / GB, rd / (1024 * pg) * 0.3e-3))
# LSM compaction through Raft (round 4 blocker). Default policy: full compaction when series >= 10; every index over
# 10,240 pages is rewritten in full on every round, and rounds trigger every 10 mutable pages.
for R, perday, lbl in ((R0, 200e3, 'today'), (R3, 835e3, '3x pace')):
    rd = R * 30; pages_day = perday * 30 / (16 * KB); rounds = pages_day / 10
    fts = min(hot_records(R) * 480 / 4, 18.5e9)   # current txt_<p> FULL_TEXT, about a quarter of the hot tier, capped by the 120 GB partition
    fts_pages_day = perday * UNIQ * 0.8 * 600 / (256 * KB); fts_rounds = fts_pages_day / 10
    print('compaction default policy %s: recdir %.1f GB, %.0f mutable pages/day -> %.0f full rewrites/day = %.0f GB/day through Raft; current FULL_TEXT %.1f GB, %.0f rewrites/day = %.0f GB/day; budgeted ingest deltas 15 to 25'
          % (lbl, rd / GB, pages_day, rounds, rounds * rd / GB, fts / GB, fts_rounds, fts_rounds * fts / 2 / GB))
# Round 6: point-lookup indexes are HASH (HashIndex.compact() returns false), so they left the schedule. Weekly: the current
# FULL_TEXT (18.5 GB) and the identity LSM indexes (Target, Watch, Mission composites, AuditEntry and AuditAccess chains,
# Evidence FULL_TEXT, df table: about 3 GB at 3x). At seal and after admission: every LSM index of the partition, 27
# composites x 77M x 40 B, one 3 GB index at a time, once per 3.2 months at 3x pace.
IDENTITY_LSM_GB, SEAL_COMPOSITES_GB, MONTHS_PER_PERIOD = 3, 27 * 77e6 * 40 / GB, 3.2
weekly = 18.5 + IDENTITY_LSM_GB + SEAL_COMPOSITES_GB / (MONTHS_PER_PERIOD * 30 / 7)
print('compaction SPORK policy (round 6): weekly current FULL_TEXT 18.5 + identity LSM %.0f + seal compactions %.0f GB per period averaged %.1f = %.0f GB/week = %.1f GB/day at mid 3x; one at a time, largest 18.5 GB (27 GB kept as the Raft-line reserve); round 4 had 80 GB/week with LSM point lookups'
      % (IDENTITY_LSM_GB, SEAL_COMPOSITES_GB, SEAL_COMPOSITES_GB / (MONTHS_PER_PERIOD * 30 / 7), weekly, weekly / 7))
# Live composite series between weekly full compactions: one series per incremental round of minPagesSchedule pages of 64 KB
for perday, lbl in ((200e3, 'today'), (835e3, '3x pace')):
    for sched in (64, 4):
        rounds = perday * 40 / (sched * 64 * KB)
        print('  live composite %s, schedule %d: %.0f rounds/day, %.0f series at week end; cursor open about %.0f cached touches (2 per series + up to %d mutable pages); incremental Raft overhead about %.0f%% of merged bytes (3 x 64 KB per round)'
              % (lbl, sched, rounds, rounds * 7, 2 * rounds * 7 + sched, sched, 3 * 64 / (sched * 64) * 100))
print('  identity AuditEntry composite at 20 GB/yr of audit: %.1f GB/yr of entries; fullSeriesThreshold=3 would rewrite it every 3 rounds = every %.1f days = %.1f GB/day and rising'
      % (20e9 / 300 * 40 / GB, 3 * 64 * 64 * KB / (20e9 / 300 * 40 / 365), (20e9 / 300 * 40 / GB) / (3 * 64 * 64 * KB / (20e9 / 300 * 40 / 365))))
print('Raft line %d GB: %d x (%d ingest + %d full compaction + %d incremental) per day; dead-peer window at 25 to 33 GB/day with compactions suspended: %.1f to %.1f days; with one full compaction %.1f days'
      % (RAFT_LINE, 2, RAFT_INGEST_PER_DAY, RAFT_FULL_COMPACTION, RAFT_INCREMENTAL_PER_DAY, RAFT_LINE / 33, RAFT_LINE / 25, RAFT_LINE / 60))
# Stale-follower hazard (round 4): entries per second during an admission stall at 3x pace
mj = (1.25e6 + 626e3) * 2 / 86400
print('MediaJob entries/s at 3x pace (claim + complete per job, unbatched): %.0f; 10,000-entry lag threshold reached in %.0f s of a 6 to 10 minute stall; batched 50 per tx: %.1f/s, %.0f s'
      % (mj, 10000 / mj, mj / 50, 10000 / (mj / 50)))
# Audit rate (round 4): per-view image grants instead of per-fetch appends
analysts = 20
print('audit rate with %d concurrent analysts at 1 intake/min each (intake + face scan image.view + dossier footprint + 2 searches = 5 entries): %.1f/s; per-fetch image audit at 120 x 8 = 960 fetches per face scan would add %.0f/s'
      % (analysts, analysts * 5 / 60, analysts * 960 / 60))

# Admission stall (3.5): snapshot slots on the leader; follower retries at base x 2^(n-1), no jitter.
def stall(T, base, retries, slots):
    sched = [0.0]; t = 0.0
    for n in range(1, retries + 1):
        t += base * 2 ** (n - 1); sched.append(t)
    busy = []; ends = []
    pending = [0, 1, 2]
    for at in sched:
        busy = [e for e in busy if e > at]
        won = []
        for f in pending:
            if len(busy) < slots:
                busy.append(at + T * (3 if slots >= 3 else 1)); ends.append(busy[-1]); won.append(f)
        pending = [f for f in pending if f not in won]
        if not pending:
            break
    return (max(ends) if not pending else None), len(pending)
for gb in (120, 40):
    for bw in (1.0, 0.6):
        T = gb / bw
        a, fa = stall(T, 30, 6, 1); c, _ = stall(T, 30, 6, 3)
        print('admission %d GB at %.1f GB/s: concurrency 1 (30 s x 6) last follower %.1f min%s; concurrency 3 %.1f min'
              % (gb, bw, a / 60 if a else float('nan'), ' (%d gave up)' % fa if fa else '', c / 60))
F = m(R0, 'mid')['nofaces'] - FIXED - HEAD
for bw in (1.0, 0.6):
    T = F / bw
    a, fa = stall(T, 30, 6, 1)
    print('formation %.0f GB per follower at %.1f GB/s: concurrency 1 -> %s; concurrency 3 all done at %.0f min'
          % (F, bw, ('%d follower(s) exhaust 7 attempts' % fa) if fa else '%.0f min' % (a / 60), 3 * T / 60))

db0, db3 = m(R0, 'mid')['db'], m(R3, 'mid')['db']
print('leader hash pass at 1 to 2 GB/s: today %.0f to %.0f min (%.0f GB), mid 3x %.0f to %.0f min (%.0f GB)'
      % (db0 / 2 / 60, db0 / 60, db0, db3 / 2 / 60, db3 / 60, db3))
print('full-node acquire mid 3x at 0.6 to 1.0 GB/s: %.0f to %.0f min; formation 3 x %.0f GB = %.2f TB: %.0f to %.0f min; rsync seed at 0.95 to 1.25 GB/s %.0f to %.0f min'
      % (db3 / 60, db3 / 0.6 / 60, F, 3 * F / 1e3, 3 * F / 60, 3 * F / 0.6 / 60, F / 1.25 / 60, F / 0.95 / 60))

# ---------------------------------------------------------------------------------------------------------------------
# Round 8 (owner directive 2026-11-03: the System 1 multimodal index ships from the beginning, with Recon's Q1 move) and
# round 9 (owner, same day: engineering time is not a planning constraint, so the plan follows gates, not capacity).
# S-PDQ and S-TEXT ship with Recon's Q1 move once H1 is in; S-FACE, S-IMG, S-TATTOO and S-DOC follow in one Q2 tier-A
# image pass once P0d has answered (ROC, DINOv3, TraffickCam); S-STYLE moves onto the IVF index in Q2.
# Weeks count from Monday 2026-11-02 (week 1); a quarter is 13 weeks; month m is START + m x 30.44 days.
print()
print('rounds 8 and 9: System 1 from the beginning, scheduled by gates')
Q1_START = datetime.date(2026, 11, 2)


def week_start(w):
    return Q1_START + datetime.timedelta(days=7 * (w - 1))


def month_of_day(d):
    return (d - START).days / 30.4375


def day_of_month(mo):
    return START + datetime.timedelta(days=mo * 30.4375)


# Per-space S1 bytes per record (the split of s1_bytes_per_record; SPANN boundary replication on the posting side only).
def s1_space_bytes(sc):
    dimg = S[sc]['img'] * 0.5; reps = dimg * S[sc]['fpi'] / 2.5
    b = S1_BOUNDARY
    return {'pdq': dimg * S1_PDQ * (1 + b),
            'text': UNIQ * (S1_TEXT_POST * (1 + b) + S1_TEXT_RERANK),
            'face': reps * (S1_FACE_POST * (1 + b) + S1_FACE_INT8),
            'img': S1_IMG_REP * dimg * (S1_IMG_POST * (1 + b) + S1_IMG_RERANK),
            'tattoo': dimg * TATTOO_PER_DIMG * S1_TATTOO * (1 + b)}


for sc in S:
    sb = s1_space_bytes(sc)
    assert abs(sum(sb.values()) - s1_bytes_per_record(sc)) < 1e-6
FIRST = ('pdq', 'text')                       # WS15a, Q1
Q2_SPACES = FIRST + ('face',)                 # + WS15b
ALL = Q2_SPACES + ('img', 'tattoo')           # + S-IMG, S-TATTOO (and S-DOC 2 GB fixed); round 9: all in Q2


def s1_gb(R, sc, spaces):
    sb = s1_space_bytes(sc)
    return R * sum(sb[k] for k in spaces) / GB + (S1_DOC_GB if 'img' in spaces else 0)


sb = s1_space_bytes('mid')
print('S1 bytes per record at mid by space: ' + ', '.join('%s %.1f' % (k, sb[k]) for k in ALL) + ' = %.1f; first spaces (S-PDQ + S-TEXT) %.1f B, + S-FACE %.1f B'
      % (sum(sb.values()), sb['pdq'] + sb['text'], sb['pdq'] + sb['text'] + sb['face']))
for sc in S:
    print('  %s today: first spaces %.1f GB, + S-FACE %.1f GB, all spaces %.1f GB; 3x first spaces %.1f GB'
          % (sc, s1_gb(R0, sc, FIRST), s1_gb(R0, sc, Q2_SPACES), s1_gb(R0, sc, ALL), s1_gb(R3, sc, FIRST)))

Q1_EXIT_MO, Q2_EXIT_MO = 2.9, 5.9
q1_exit = week_start(13) + datetime.timedelta(days=4); q2_exit = week_start(26) + datetime.timedelta(days=4)
print('Q1 exit %s = month %.1f; Q2 exit %s = month %.1f' % (q1_exit, month_of_day(q1_exit), q2_exit, month_of_day(q2_exit)))

# Data deadlines the plan is built around (each printed with its arithmetic elsewhere in this model).
ff_mid = cross(lambda R: m(R, 'mid')['nofaces'], CEIL)
print('deadlines at mid: face-free footprint crosses 1,560 GB month %.1f (%s, H1); identity crosses 120 GB month %.1f (%s, E1); hot tier reaches 200M month %.1f (%s, first aging, WS7); '
      'vector slice with the style graphs month %.1f (%s, S-STYLE onto IVF); ROC representatives pass 205M month %.1f (%s, H2); second-drive order month %.1f (%s)'
      % (ff_mid, day_of_month(ff_mid), cross(lambda R: m(R, 'mid')['ident'], 120), day_of_month(cross(lambda R: m(R, 'mid')['ident'], 120)),
         (200e6 - 110e6) / (GROWTH - 6.1e6), day_of_month((200e6 - 110e6) / (GROWTH - 6.1e6)),
         cross(lambda R: vector_slice_gb(R, 'mid', STYLE_CACHE_HALVED_GB), VECTOR_SLICE_GB), day_of_month(cross(lambda R: vector_slice_gb(R, 'mid', STYLE_CACHE_HALVED_GB), VECTOR_SLICE_GB)),
         cross(lambda R: m(R, 'mid')['reps'] * 1e6, CLUSTER_CAP), day_of_month(cross(lambda R: m(R, 'mid')['reps'] * 1e6, CLUSTER_CAP)),
         cross(lambda R: m(R, 'mid')['tot'], 0.8 * CEIL_X1), day_of_month(cross(lambda R: m(R, 'mid')['tot'], 0.8 * CEIL_X1))))

# H1 gates every backfill: the S1 codes cannot go on 2 TB. Face-free plus the first spaces crosses 1,560 GB at:
for sc in S:
    ff = cross(lambda R, sc=sc: m(R, sc)['nofaces'], CEIL)
    ffs = cross(lambda R, sc=sc: m(R, sc)['nofaces'] + s1_gb(R, sc, FIRST), CEIL)
    at_q1 = m(R0 + GROWTH * Q1_EXIT_MO, sc)['nofaces'] + s1_gb(R0 + GROWTH * Q1_EXIT_MO, sc, FIRST)
    print('  %s: face-free crosses 1,560 at month %s (%s); face-free + first spaces at month %s (%s); at the Q1 exit face-free + first spaces = %.0f GB (%.0f%%)'
          % (sc, ff, date(ff) if ff is not None else '-', ffs, date(ffs) if ffs is not None else '-', at_q1, at_q1 / CEIL * 100))

# Phased footprint at mid after H1 (round 9 phases against round 7: no S1 before Q3, first spaces in Q3, the rest in year 2).
print('phased mid footprint after H1 (GB): no-S1 base, round-9 S1, round-9 total (%% of 4,520) | round-7 S1, round-7 total')
for lbl, mo, faces, s9, s7 in (('Q1 exit', Q1_EXIT_MO, False, FIRST, ()), ('Q2 exit', Q2_EXIT_MO, True, ALL, ()),
                               ('Q3 exit', 8.9, True, ALL, Q2_SPACES), ('Q4 exit', 11.9, True, ALL, Q2_SPACES)):
    R = R0 + GROWTH * mo; o = m(R, 'mid', False)
    base = o['tot'] if faces else o['nofaces']
    a9, a7 = s1_gb(R, 'mid', s9), s1_gb(R, 'mid', s7)
    print('  %-7s month %4.1f: base %.0f, S1 %.0f, total %.0f (%.0f%%) | round 7 S1 %.0f, total %.0f; S1 arrives %.0f GB earlier'
          % (lbl, mo, base, a9, base + a9, (base + a9) / CEIL_X1 * 100, a7, base + a7, a9 - a7))
x1 = cross(lambda R: m(R, 'mid')['tot'], 0.8 * CEIL_X1)
print('  second-drive order trigger (3,616 GB) at mid: month %s (%s), unchanged: it already counted every space from month 0, and every space lands before it'
      % (x1, date(x1)))

# Vector residency slice by phase. The cache is halved to 0.25M per bucket with WS15a in Q1; S-STYLE leaves jvector in Q2.
def slice_phased(R, spaces, cache=STYLE_CACHE_HALVED_GB, style_on_ivf=False, ipp=S1_ITEMS_PER_POSTING):
    c = s1_centroids(R, 'mid')
    cent = sum(c[k] * S1_CENTROID_B[k] * (1 if k == 'pdq' else S1_ITEMS_PER_POSTING / ipp) for k in spaces) / GB
    return (style_ivf_gb(R) if style_on_ivf else style_jvector_gb(R)) + cache + cent


for lbl, mo, sp, on_ivf in (('Q1 exit', Q1_EXIT_MO, FIRST, False), ('Q2 exit', Q2_EXIT_MO, ALL, True), ('3x', 24.0, ALL, True)):
    R = R0 + GROWTH * mo
    print('  vector slice %-7s (month %4.1f): style %s %.2f + cache 0.4 + centroids %.2f = %.1f GB; at 66 items per posting %.1f GB'
          % (lbl, mo, 'IVF' if on_ivf else 'jvector', style_ivf_gb(R) if on_ivf else style_jvector_gb(R),
             slice_phased(R, sp, style_on_ivf=on_ivf) - (style_ivf_gb(R) if on_ivf else style_jvector_gb(R)) - 0.4,
             slice_phased(R, sp, style_on_ivf=on_ivf), slice_phased(R, sp, style_on_ivf=on_ivf, ipp=66)))

# IVF gate threshold (P0a): the smallest items per posting (dense spaces; S-PDQ stays at 1,000) for which the slice still
# fits 8 GB at 3x once S-STYLE is on the IVF index, with the halved cache.
def heap_at(R, sc, ipp):
    c = s1_centroids(R, sc)
    dense = sum(c[k] * S1_CENTROID_B[k] for k in ('img', 'text', 'face', 'tattoo')) * S1_ITEMS_PER_POSTING / ipp
    return (dense + c['pdq'] * S1_CENTROID_B['pdq']) / GB


for sc in ('mid', 'high'):
    for ipp in range(200, 0, -1):
        if heap_at(R3, sc, ipp) + STYLE_CACHE_HALVED_GB + style_ivf_gb(R3) > VECTOR_SLICE_GB:
            break
    print('  IVF gate floor %s 3x: slice fits 8 GB down to %d items per posting (heap %.2f GB at %d; %.2f GB at 200)'
          % (sc, ipp + 1, heap_at(R3, sc, ipp + 1), ipp + 1, heap_at(R3, sc, 200)))
for parts, npr in ((4, 64), (4, 128), (12, 128)):
    print('  IVF scan %d partitions at nprobe %d: %.1f ms I/O + %.1f ms compute = %.1f ms'
          % (parts, npr, parts * npr / POOL * IO_US / 1000, parts * COMPUTE_MS_PER_PARTITION * npr / 64, parts * npr / POOL * IO_US / 1000 + parts * COMPUTE_MS_PER_PARTITION * npr / 64))

# Fallback if the gate fails: S-TEXT on the existing LSM_VECTOR (jvector, BINARY scan, searchCacheSize capped so codes are
# read through the page cache) at 36 B resident per vector (32 B location + 4 B ordinal, workstreams/vector-scale.md);
# S-PDQ on the hot-window pdqBands (radius 15, already in media, WS12); S-FACE stays ROC-only (interactive representative
# gallery + exhaustive job). Unique texts per month at today's pace: GROWTH x UNIQ.
LSMV_RESIDENT_B = 36
per_month = GROWTH * UNIQ
print('fallback (gate fails): S-TEXT on LSM_VECTOR at %d B resident; unique texts %.1fM per month; corpus-wide today %.0fM x %d B = %.1f GB (over the slice)'
      % (LSMV_RESIDENT_B, per_month / 1e6, R0 * UNIQ / 1e6, LSMV_RESIDENT_B, R0 * UNIQ * LSMV_RESIDENT_B / GB))
for mo in (Q1_EXIT_MO, 4.7, 12.0):
    R = R0 + GROWTH * mo
    room = VECTOR_SLICE_GB - style_jvector_gb(R) - STYLE_CACHE_HALVED_GB
    n = room * GB / LSMV_RESIDENT_B
    print('  month %4.1f: slice room %.1f GB (8 - style %.1f - cache 0.4) = %.0fM vectors = the newest %.1f months of unique text'
          % (mo, room, style_jvector_gb(R), n / 1e6, n / per_month))

# Encode compute (a calendar gate, not an engineering one). Q1: S-TEXT on the GPU burst host at 1.2 ms per unique text; S-PDQ
# on CPU at PDQ_CORE_MS per distinct image including the libvips decode (planning figure, measured with A16 in P0a).
# Q2: one tier-A image pass (YuNet + AuraFace + the scene model + text presence, 3.5 ms) feeds S-FACE and S-IMG, plus OCR.
PDQ_CORE_MS = 10
print('Q1 first-spaces backfill mid today: S-TEXT %.0fM x 1.2 ms = %.0f GPU-h (%.1f days on one card); S-PDQ %.0fM x %d ms = %.0f core-days = %.1f days on 64 cores'
      % (R0 * UNIQ / 1e6, R0 * UNIQ * 1.2e-3 / 3600, R0 * UNIQ * 1.2e-3 / 3600 / 24, dimg0 / 1e6, PDQ_CORE_MS, dimg0 * PDQ_CORE_MS / 1000 / 86400, dimg0 * PDQ_CORE_MS / 1000 / 86400 / 64))
q2_gpu_h = dimg0 * 3.5e-3 / 3600 + dimg0 * 0.35 * 10e-3 / 3600
print('Q2 image pass mid today: tier A %.0fM x 3.5 ms = %.0f GPU-h + OCR %.0fM x 10 ms = %.0f GPU-h = %.0f GPU-h: %.0f days on one card, %.0f days on four; then %.0fM face representatives indexed'
      % (dimg0 / 1e6, dimg0 * 3.5e-3 / 3600, dimg0 * 0.35 / 1e6, dimg0 * 0.35 * 10e-3 / 3600, q2_gpu_h, q2_gpu_h / 24, q2_gpu_h / 24 / 4, m(R0, 'mid')['reps']))
o0 = m(R0, 'mid')
mount0 = 8 * (o0['ident'] + o0['ops'] + o0['audit'] + o0['rdir'] + 120 + 73 + 37) / 2 + max(o0['db'] - (o0['ident'] + o0['ops'] + o0['audit'] + o0['rdir']) - (120 + 73 + 37), 0) / 2 + 60
fs_daily = 75e6 * (sb['pdq'] + sb['text']) / GB / 2; fs_sealed = max(s1_gb(R0, 'mid', FIRST) - 75e6 * (sb['pdq'] + sb['text']) / GB, 0) / 2
print('backup mount mid today with first-spaces codes only (Q1): %.0f + daily +%.0f GB x 8 + sealed +%.0f GB = %.0f GB'
      % (mount0, fs_daily, fs_sealed, mount0 + 8 * fs_daily + fs_sealed))
c0 = s1_centroids(R0, 'mid')
print('S1 centroid heap mid today by space (GB): ' + ', '.join('%s %.2fM x %d = %.2f' % (k, c0[k] / 1e6, S1_CENTROID_B[k], c0[k] * S1_CENTROID_B[k] / GB) for k in ALL))

# ---------------------------------------------------------------------------------------------------------------------
# Round 10 (owner directives 2026-11-03: "do as much as we can with the hardware we already have; expect hardware to not
# change", then "plan on expanding the hard drives to 4 TB"). Fixed for the whole horizon: 4 nodes, 126 GB RAM, no GPU
# (no burst host, no inference card), no new storage except the 4 TB drives. H2 and the second drive are void.
print()
print('round 10: fixed hardware, 4 TB drives')


def ceil_of(usable):
    return min(0.8 * usable + HEAD, 0.9 * usable)


# 4 TB per node (owner, confirmed 2026-11-03): one 4 TB drive replaces the 2 TB; nothing is added on top. Planned at
# 3,600 GB usable, the 2 TB class's ratio (1,800 of 2,000); A12's method on a 3.84 TB enterprise drive would give
# (3,840 - 60) x 0.985 = 3,723, planned at 3,700 (ceiling 3,080), so 3,600 is the conservative reading.
USABLE_4TB, USABLE_4TB_A12 = 3600, 3700
CEIL_4TB = ceil_of(USABLE_4TB)
print('4 TB ceiling (replacement, confirmed): usable %d, ceiling min(%.0f, %.0f) = %.0f (trigger %.0f); A12 method on a 3.84 TB drive: usable %d, ceiling %.0f'
      % (USABLE_4TB, 0.8 * USABLE_4TB + HEAD, 0.9 * USABLE_4TB, CEIL_4TB, 0.8 * CEIL_4TB, USABLE_4TB_A12, ceil_of(USABLE_4TB_A12)))


def fmt(mo):
    return 'month %4.1f (%s)' % (mo, date(mo)) if mo is not None else 'beyond month 60'


VARIANTS = (('faces + every S1 space', lambda R, sc: m(R, sc)['tot']),
            ('faces + first spaces', lambda R, sc: m(R, sc, False)['tot'] + s1_gb(R, sc, FIRST)),
            ('faces, no S1', lambda R, sc: m(R, sc, False)['tot']),
            ('face-free, no S1', lambda R, sc: m(R, sc)['nofaces']))
print('runway (full replicas): first crossing of the 2 TB ceiling 1,560 and the 4 TB ceiling %.0f' % CEIL_4TB)
for sc in S:
    for lbl, f in VARIANTS:
        print('  %-4s %-24s 2 TB %s | 4 TB %s | 3x %.0f GB (%.0f%% of %.0f)'
              % (sc, lbl, fmt(cross(lambda R, sc=sc, f=f: f(R, sc), CEIL)), fmt(cross(lambda R, sc=sc, f=f: f(R, sc), CEIL_4TB)),
                 f(R3, sc), f(R3, sc) / CEIL_4TB * 100, CEIL_4TB))


# Options (GB saved per node at R). 'warm' = records older than the hot tier (newest 18 months, 200M cap).
def warm_bytes(R, sc):
    o = m(R, sc); hot = hot_records(R); w = (R - hot) / R
    return (R - hot) * o['bpr_rec'] / GB + o['tw'] + w * (o['media'] - o['band']) + w * (o['s1'] - S1_DOC_GB)


def opt(R, sc):
    o = m(R, sc); hot = hot_records(R); sb_ = s1_space_bytes(sc); reps = S[sc]['img'] * 0.5 * S[sc]['fpi'] / 2.5
    return {
        'v3 hot cap 200M -> 150M': min(50e6, max(hot - 150e6, 0)) * (TXT_HOT - TXT_WARM) / GB,
        'v1 warm FTS pruning': (R - hot) * WARM_PRUNE_SAVE / GB,
        'v5 S-FACE rerank int8 -> 4-bit': R * reps * (S1_FACE_INT8 - S1_FACE_4BIT) / GB,
        'S-FACE no rerank (ROC rescores)': R * reps * S1_FACE_INT8 / GB,
        'S-IMG + S-TATTOO hot window only': (R - hot) * (sb_['img'] + sb_['tattoo']) / GB,
        'S-TEXT rerank 4-bit -> none on warm': (R - hot) * UNIQ * S1_TEXT_RERANK / GB,
        'v2 non-rep gallery off node (store)': o['gal'] * 0.6,
        'v4 warm text off node (E17 + store)': (R - hot) * WARM_TEXT_OFF_SAVE / GB,
        'RF2 warm partitions (2 of 4 nodes)': 0.5 * warm_bytes(R, sc),
        'retention: warm off node (store or upstream)': warm_bytes(R, sc),
    }


print('options at mid (GB saved per node): month 6 / month 12 / month 24 (3x)')
o6, o12, o24 = opt(R0 + GROWTH * 6, 'mid'), opt(R0 + GROWTH * 12, 'mid'), opt(R3, 'mid')
for k in o24:
    print('  %-46s %6.0f %6.0f %6.0f' % (k, o6[k], o12[k], o24[k]))
print('  warm bytes at mid 3x: %.0f GB (rec %.0f + txt warm %.0f + media %.0f + S1 %.0f)'
      % (warm_bytes(R3, 'mid'), (R3 - hot_records(R3)) * m(R3, 'mid')['bpr_rec'] / GB, m(R3, 'mid')['tw'],
         (R3 - hot_records(R3)) / R3 * (m(R3, 'mid')['media'] - m(R3, 'mid')['band']), (R3 - hot_records(R3)) / R3 * (m(R3, 'mid')['s1'] - S1_DOC_GB)))

COMBOS = (('none', ()),
          ('A no store, no engine work: v3 + v1 + v5 + S-IMG/S-TATTOO hot only',
           ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit', 'S-IMG + S-TATTOO hot window only')),
          ('B = A + object store: v2 + v4',
           ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit', 'S-IMG + S-TATTOO hot window only',
            'v2 non-rep gallery off node (store)', 'v4 warm text off node (E17 + store)')),
          ('C = A + RF2 warm partitions',
           ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit', 'S-IMG + S-TATTOO hot window only',
            'RF2 warm partitions (2 of 4 nodes)')),
          ('D = A + retention (warm off node)',
           ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit', 'S-IMG + S-TATTOO hot window only',
            'retention: warm off node (store or upstream)')))
COMBO_SAVE = {}
print('combinations against the 4 TB ceiling (faces + every S1 space; v1 is computed on the full warm text, so it is not stacked with v4 or retention twice)')
for lbl, keys in COMBOS:
    def tot_after(R, sc, keys=keys):
        o = opt(R, sc)
        sv = sum(o[k] for k in keys)
        if 'v4 warm text off node (E17 + store)' in keys or 'retention: warm off node (store or upstream)' in keys or 'RF2 warm partitions (2 of 4 nodes)' in keys:
            # v1 prunes warm FTS postings; retention removes them, and RF2 halves them, so do not count v1 on top of either.
            if 'retention: warm off node (store or upstream)' in keys:
                sv -= o['v1 warm FTS pruning'] + o['S-IMG + S-TATTOO hot window only']
            if 'RF2 warm partitions (2 of 4 nodes)' in keys:
                sv -= 0.5 * (o['v1 warm FTS pruning'] + o['S-IMG + S-TATTOO hot window only'])
        return m(R, sc)['tot'] - sv
    COMBO_SAVE[lbl] = tot_after
    print('  %s' % lbl)
    for sc in S:
        c = cross(lambda R, sc=sc: tot_after(R, sc), CEIL_4TB)
        print('     %-4s crosses %.0f at %s; 3x total %.0f GB (%.0f%%)' % (sc, CEIL_4TB, fmt(c), tot_after(R3, sc), tot_after(R3, sc) / CEIL_4TB * 100))
# E = A + RF2 + the object-store valves (the store is unconfirmed: 9.2 Q4).
E_KEYS = ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit', 'S-IMG + S-TATTOO hot window only',
          'RF2 warm partitions (2 of 4 nodes)', 'v2 non-rep gallery off node (store)', 'v4 warm text off node (E17 + store)')


def tot_E(R, sc):
    o = opt(R, sc)
    # RF2 halves the warm text; v4 then removes the stored text from the remaining copy: count v4 at half.
    sv = sum(o[k] for k in E_KEYS) - 0.5 * (o['v1 warm FTS pruning'] + o['S-IMG + S-TATTOO hot window only']) - 0.5 * o['v4 warm text off node (E17 + store)']
    return m(R, sc)['tot'] - sv


print('  E = C + object store: v2 + v4 (v4 at half under RF2)')
for sc in S:
    c = cross(lambda R, sc=sc: tot_E(R, sc), CEIL_4TB)
    print('     %-4s crosses %.0f at %s; 3x total %.0f GB (%.0f%%)' % (sc, CEIL_4TB, fmt(c), tot_E(R3, sc), tot_E(R3, sc) / CEIL_4TB * 100))
# Retention window that keeps mid 3x under the 4 TB ceiling with combo A: how many records can stay on node at 3x.
fA = COMBO_SAVE['A no store, no engine work: v3 + v1 + v5 + S-IMG/S-TATTOO hot only']
over = fA(R3, 'mid') - CEIL_4TB
per_warm_rec = warm_bytes(R3, 'mid') * GB / (R3 - hot_records(R3))
print('  retention sized for mid 3x on 4 TB after A: over by %.0f GB; a warm record costs %.0f B on node, so the oldest %.0fM records leave and the newest %.0fM of %.0fM stay (%.0f%%)'
      % (over, per_warm_rec, over * GB / per_warm_rec / 1e6, (R3 - over * GB / per_warm_rec) / 1e6, R3 / 1e6, (R3 - over * GB / per_warm_rec) / R3 * 100))
# RF2 layout: at 3x, partitions of about 75M records; hot partitions hold the newest 200M.
parts3 = R3 / 75e6; hot_parts = 200e6 / 75e6
print('  RF2 at 3x: %.0f partitions, %.1f hot (4 copies), %.1f warm on 2 of 4 nodes each: about %.1f warm partitions per node, every warm partition on one of n2/n3 and one of n1/n4'
      % (parts3, hot_parts, parts3 - hot_parts, (parts3 - hot_parts) / 2))

# RAM on fixed hardware: ROC cap without H2, vector slice, decider.
for sc in S:
    mo = cross(lambda R, sc=sc: m(R, sc)['reps'] * 1e6, CLUSTER_CAP)
    print('  ROC: %s representatives pass the %.0fM failover-safe cap at %s; 3x %.0fM' % (sc, CLUSTER_CAP / 1e6, fmt(mo), m(R3, sc)['reps']))

# CPU-only encoding on the 16 encode cores cluster-wide (4c.4). Planning figures, measured in P0a with A16: PDQ 10 ms per
# image (above); gte-multilingual-base int8 on CPU 60 ms per unique text (a 12-layer 768-wide encoder over about 300
# tokens); YuNet 5 ms per image; AuraFace-v1 ResNet100 int8 0.25 core-s per face; the scene model 0.5 core-s per image
# (A16: about 2 images/s per core); PP-OCRv5 1.75 s per text-bearing image (A16). ROC DETECT 0.1 core-s per image on the
# roc-work cgroups (6 cores on n4, 2 on each of n1 to n3 = 12).
ENC_CORES, ROC_WORK_CORES = 16, 12
TEXT_CPU_S, YUNET_S, AURA_S, SCENE_S, OCR_S, ROC_DETECT_S = 0.060, 0.005, 0.25, 0.5, 1.75, 0.1
reps0 = m(R0, 'mid')['reps'] * 1e6
day = lambda core_s, cores=ENC_CORES: core_s / cores / 86400
print('CPU-only backfill at mid today on %d encode cores (days):' % ENC_CORES)
bf = {'S-PDQ (every distinct image)': dimg0 * PDQ_CORE_MS / 1000, 'S-TEXT (unique texts)': R0 * UNIQ * TEXT_CPU_S,
      'S-FACE (YuNet on every image + AuraFace on representatives)': dimg0 * YUNET_S + reps0 * AURA_S,
      'S-IMG (scene model on PDQ-cluster representatives, 30%)': dimg0 * S1_IMG_REP * SCENE_S,
      'S-IMG on every distinct image': dimg0 * SCENE_S, 'OCR (text-bearing 35%)': dimg0 * 0.35 * OCR_S}
for k, v in bf.items():
    print('  %-60s %.1fM core-s = %.1f days' % (k, v / 1e6, day(v)))
print('  day-one spaces (S-PDQ + S-TEXT): %.1f days; + S-FACE: %.1f; + S-IMG on representatives: %.1f days; ROC DETECT for the face backfill on the %d roc-work cores: %.0f days in parallel'
      % (day(bf['S-PDQ (every distinct image)'] + bf['S-TEXT (unique texts)']),
         day(bf['S-PDQ (every distinct image)'] + bf['S-TEXT (unique texts)'] + bf['S-FACE (YuNet on every image + AuraFace on representatives)']),
         day(bf['S-PDQ (every distinct image)'] + bf['S-TEXT (unique texts)'] + bf['S-FACE (YuNet on every image + AuraFace on representatives)'] + bf['S-IMG (scene model on PDQ-cluster representatives, 30%)']),
         ROC_WORK_CORES, day(dimg0 * ROC_DETECT_S, ROC_WORK_CORES)))
for perday, lbl in ((300e3, 'today'), (1.25e6, '3x pace')):
    rec_day = perday / 1.5
    parts = {'PDQ': perday * PDQ_CORE_MS / 1000, 'text': rec_day * UNIQ * TEXT_CPU_S, 'YuNet': perday * YUNET_S,
             'AuraFace reps': perday * 0.5 / 2.5 * AURA_S, 'scene on reps': perday * S1_IMG_REP * SCENE_S}
    h = sum(parts.values()) / ENC_CORES / 3600
    ocr_h = perday * 0.35 * OCR_S / ENC_CORES / 3600
    print('  daily stream %s (%.2fM distinct images/day, %.0fK records): %s = %.1f h on %d cores; OCR on every text-bearing image +%.1f h, on 10%% (case- and target-linked) +%.1f h'
          % (lbl, perday / 1e6, rec_day / 1e3, ', '.join('%s %.2f h' % (k, v / ENC_CORES / 3600) for k, v in parts.items()), h, ENC_CORES, ocr_h, ocr_h / 10))
print('  S-IMG on hot-window representatives only (%.0fM of %.0fM records today): %.1f days'
      % (hot_records(R0) / 1e6, R0 / 1e6, day(bf['S-IMG (scene model on PDQ-cluster representatives, 30%)'] * hot_records(R0) / R0)))
print('  day-one backfill on CPU finishes by the Q1 exit if it starts by %s (Q1 exit %s minus %.1f days)'
      % (q1_exit - datetime.timedelta(days=day(bf['S-PDQ (every distinct image)'] + bf['S-TEXT (unique texts)'])), q1_exit,
         day(bf['S-PDQ (every distinct image)'] + bf['S-TEXT (unique texts)'])))
TRIG_4TB = 0.8 * CEIL_4TB
print('trigger dates at %.0f GB (80%% of the 4 TB ceiling), when the next runway step must be ready:' % TRIG_4TB)
for lbl in COMBO_SAVE:
    print('  %-70s ' % lbl[:70] + ' | '.join('%s %s' % (sc, fmt(cross(lambda R, sc=sc: COMBO_SAVE[lbl](R, sc), TRIG_4TB))) for sc in S))
print('  %-70s ' % 'E' + ' | '.join('%s %s' % (sc, fmt(cross(lambda R, sc=sc: tot_E(R, sc), TRIG_4TB))) for sc in S))
# Owner decisions 2026-11-03: "Plan C" (A + E26), then "Approve both" (scene and tattoo on the hot window only, option 4,
# part of A; OCR on the daily stream only). C without option 4 is kept for the record.
C_NO4 = ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit', 'RF2 warm partitions (2 of 4 nodes)')


def tot_C_no4(R, sc):
    o = opt(R, sc)
    return m(R, sc)['tot'] - (sum(o[k] for k in C_NO4) - 0.5 * o['v1 warm FTS pruning'])


print('decided plan C without the hot-window scene and tattoo option (still open):')
for sc in S:
    print('  %-4s crosses %.0f at %s; trigger %.0f at %s; 3x %.0f GB (%.0f%%)' % (
        sc, CEIL_4TB, fmt(cross(lambda R, sc=sc: tot_C_no4(R, sc), CEIL_4TB)), TRIG_4TB, fmt(cross(lambda R, sc=sc: tot_C_no4(R, sc), TRIG_4TB)),
        tot_C_no4(R3, sc), tot_C_no4(R3, sc) / CEIL_4TB * 100))
A_NO4 = ('v3 hot cap 200M -> 150M', 'v1 warm FTS pruning', 'v5 S-FACE rerank int8 -> 4-bit')
for sc in ('mid',):
    f = lambda R, sc=sc: m(R, sc)['tot'] - sum(opt(R, sc)[k] for k in A_NO4)
    print('  E26 deadline without the hot-window option (A minus option 4 reaches %.0f): %s; it crosses %.0f at %s'
          % (TRIG_4TB, fmt(cross(f, TRIG_4TB)), CEIL_4TB, fmt(cross(f, CEIL_4TB))))
