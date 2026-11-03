-- =====================================================================================================================
-- media_<p> (1.2, 2.1): Image by content sha, Face and Tattoo by their keys, on the rec_<p> period boundaries. The PDQ
-- columns (pdq, pdqBands and its hot-window BY ITEM index, pdqClusterRep) are WS12's and the S-IMG / S-PDQ / S-FACE /
-- S-TATTOO LSM_IVF_VECTOR indexes are E18's; neither is created here.
-- =====================================================================================================================
CREATE VERTEX TYPE Image IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Image.sha IF NOT EXISTS BINARY (mandatory true, notnull true);            -- content SHA-256, 32 B
CREATE PROPERTY Image.upstreamHash IF NOT EXISTS STRING;                                  -- Recon's hash, verbatim
CREATE PROPERTY Image.phash64 IF NOT EXISTS LONG;
CREATE PROPERTY Image.novelty IF NOT EXISTS FLOAT;
CREATE PROPERTY Image.ocrText IF NOT EXISTS LIST;
CREATE PROPERTY Image.csamHashHit IF NOT EXISTS BOOLEAN;                                  -- set before any vision model (5.7)
CREATE PROPERTY Image.mime IF NOT EXISTS STRING;
CREATE PROPERTY Image.w IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.h IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.firstSeen IF NOT EXISTS LONG;
CREATE PROPERTY Image.faceCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.tattooCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.markings IF NOT EXISTS LONG;                                        -- bitmask, as on Record
CREATE INDEX IF NOT EXISTS ON Image (sha) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Image (upstreamHash) NOTUNIQUE_HASH;                        -- Recon hash to content sha
-- Face: fc:<imageSha>:<idx>:<algo>. Gallery slot and face codes are envelope-encrypted (5.6) and written by WS-ROC.
CREATE VERTEX TYPE Face IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Face.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Face.imageSha IF NOT EXISTS BINARY;
CREATE PROPERTY Face.quality IF NOT EXISTS FLOAT;
CREATE PROPERTY Face.ageBand IF NOT EXISTS STRING;
CREATE PROPERTY Face.minorFlag IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Face.clusterKey IF NOT EXISTS STRING;
CREATE PROPERTY Face.representative IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Face.state IF NOT EXISTS STRING;                                          -- staged, searchable, tombstoned
CREATE PROPERTY Face.markings IF NOT EXISTS LONG;
CREATE INDEX IF NOT EXISTS ON Face (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Face (imageSha) NOTUNIQUE_HASH;
CREATE VERTEX TYPE Tattoo IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Tattoo.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Tattoo.imageSha IF NOT EXISTS BINARY;
CREATE PROPERTY Tattoo.markings IF NOT EXISTS LONG;
CREATE INDEX IF NOT EXISTS ON Tattoo (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Tattoo (imageSha) NOTUNIQUE_HASH;
