/*
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Writes the spork-keys golden vectors (src/test/resources/golden/*.json) from Recon's own TypeScript.
 *
 *   bun spork-keys/golden/generate-golden.ts [path/to/recon-next]
 *
 * Every "recon" value is computed by Recon's code as it is checked out, never by a copy of it: exported functions are
 * imported, and the few non-exported ones (engine.ts canonPhone/areaCodeOf/clusterKey, compare.ts normPhone, api-source.ts
 * mapRecord and its helpers) are cut verbatim out of the source file and evaluated. Inline lambdas that cannot be cut out
 * (the engine's link keys and the compare keyer) are transcribed below and the generator fails if the exact source line is
 * no longer in the file, so a Recon change cannot silently leave a stale transcription behind.
 *
 * Every "spork" value is computed by the TypeScript reference of 01-architecture.md 2.3 at the bottom of this file, the
 * seed of the TS port that ships with spork-keys. The Java test (GoldenVectorsTest) replays both: the Recon ports in
 * ReconNormalizers must equal "recon" byte for byte, SporkKeys must equal "spork".
 *
 * Read-only on the Recon repository.
 */
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const RECON = resolve(process.argv[2] ?? join(process.env.HOME ?? '', 'Documents/Projects/recon/recon-next'));
const SRC = join(RECON, 'src');
const OUT = resolve(dirname(new URL(import.meta.url).pathname), '../src/test/resources/golden');

// ── Recon, imported ──────────────────────────────────────────────────────────────────────────────────────────────────
const board = await import(join(SRC, 'ops/board.ts'));
const dossier = await import(join(SRC, 'ops/dossier.ts'));
const feed = await import(join(SRC, 'ops/feed.ts'));
const audit = await import(join(SRC, 'ops/audit.ts'));
const identityMod = await import(join(SRC, 'identity.ts'));
const engineMod = await import(join(SRC, 'engine.ts'));
const geography = await import(join(SRC, 'geography.ts'));

// ── Recon, cut out of the source ─────────────────────────────────────────────────────────────────────────────────────
const transpiler = new Bun.Transpiler({ loader: 'ts' });

/** The declaration starting at `start` up to its closing line: a top-level '}' / '};' / '})();', or the same line when it ends in ';'. */
function cut(file: string, start: string): string {
	const src = readFileSync(join(SRC, file), 'utf8');
	const at = src.indexOf(start);
	if (at < 0) throw new Error(`${file}: '${start}' not found; Recon changed, review the golden generator`);
	const lines = src.slice(at).split('\n');
	if (lines[0].trimEnd().endsWith(';')) return lines[0];
	const out: string[] = [lines[0]];
	for (let i = 1; i < lines.length; i++) {
		out.push(lines[i]);
		if (/^(\}|\};|\}\)\(\);)\s*$/.test(lines[i])) return out.join('\n');
	}
	throw new Error(`${file}: no end for '${start}'`);
}

/** Fails when a transcribed Recon line is no longer in the file verbatim. */
function requireLine(file: string, line: string): void {
	const src = readFileSync(join(SRC, file), 'utf8');
	if (!src.includes(line)) throw new Error(`${file}: transcribed line changed, update the generator: ${line}`);
}

function evaluate<T>(code: string, names: string[], deps: Record<string, unknown>): T {
	const js = transpiler.transformSync(code);
	const fn = new Function(...Object.keys(deps), `${js}\nreturn { ${names.join(', ')} };`);
	return fn(...Object.values(deps)) as T;
}

const engine = evaluate<{
	canonPhone: (p: string, r?: { city: string; state: string }) => string;
	areaCodeOf: (p: string, r?: { city: string; state: string }) => string;
	clusterKey: (by: string, v: string, r: unknown) => string;
}>(
	[cut('engine.ts', 'const PLACE_TO_AREA'), cut('engine.ts', 'function canonPhone('), cut('engine.ts', 'function areaCodeOf('),
		cut('engine.ts', 'function clusterKey(')].join('\n'),
	['canonPhone', 'areaCodeOf', 'clusterKey'],
	{ AREA_CODE_TO_PLACE: geography.AREA_CODE_TO_PLACE },
);
const compare = evaluate<{ normPhone: (p: string) => string }>(cut('compare.ts', 'const normPhone ='), ['normPhone'], {});
const apiSource = evaluate<{ mapRecord: (id: string, basic: unknown, rec: unknown) => any }>(
	[cut('api-source.ts', 'const GAZ:'), cut('api-source.ts', 'const coord ='), cut('api-source.ts', 'const toISO ='),
		cut('api-source.ts', 'const first ='), cut('api-source.ts', 'const num ='), cut('api-source.ts', 'function mapRecord(')].join('\n'),
	['mapRecord'],
	{ identity: identityMod.identity },
);

// engine.ts Engine.linkClusters identsOf, Engine.pivot keyer, compare.ts compareRecords keyer
requireLine('engine.ts', "if (linkOn.includes('email')) for (const e of r.emails) out.push(['email', e.toLowerCase(), e]);");
requireLine('engine.ts', "if (linkOn.includes('businessName')) for (const b of r.businessNames) out.push(['businessName', b.toLowerCase(), b]);");
requireLine('engine.ts', "if (linkOn.includes('social')) for (const s of r.socials) out.push(['social', `${s.platform}:${s.handle}`.toLowerCase(), `${s.platform}:${s.handle}`]);");
requireLine('engine.ts', "if (linkOn.includes('imageHash')) for (const h of r.imageHashes) out.push(['imageHash', h, h]);");
requireLine('engine.ts', "const norm = on === 'phone' ? values.map(normPhone) : values.map((v) => v.toLowerCase());");
requireLine('compare.ts', "const keyer = (v: string) => (key === 'phones' ? normPhone(v) : v.toLowerCase());");
const engineLinkLower = (v: string) => v.toLowerCase();
const engineLinkSocial = (platform: string, handle: string) => `${platform}:${handle}`.toLowerCase();

// ── SPORK reference (01-architecture.md 2.3; the seed of the TS port) ───────────────────────────────────────────────
const stripAt = (s: string) => s.replace(/^@/, '');
const spork = {
	/** Recon's engine canonPhone with the posting place: last 10 digits, a 7-digit number inherits the place's area code. */
	phone(raw: string, place?: { city: string; state: string }): { key: string | null; digits: string; inferredAreaCode: boolean } {
		let d = raw.replace(/\D/g, '');
		if (d.length > 10) d = d.slice(-10);
		let inferred = false;
		if (d.length === 7 && place) {
			const area = PLACE_TO_AREA[`${place.city}, ${place.state}`];
			if (area) { d = area + d; inferred = true; }
		}
		return { key: d ? `ph:${d}` : null, digits: d, inferredAreaCode: inferred };
	},
	email(raw: string): string | null {
		const v = stripAt(raw.trim().toLowerCase());
		return v ? `em:${v}` : null;
	},
	handle(platform: string | undefined, handle: string): string | null {
		const h = stripAt(handle.trim().toLowerCase());
		if (!h) return null;
		const p = (platform ?? '').trim().toLowerCase() || 'social';
		return `sh:${p}:${h}`;
	},
	business(raw: string): string | null {
		const v = raw.toLowerCase().replace(/[^a-z0-9]+/g, '');
		return v ? `bz:${v}` : null;
	},
	imageHash(raw: string): string | null {
		return raw ? `ih:${raw}` : null;
	},
	/** NFKD, lowercased, letters only; whitespace runs between letter runs become one space. */
	name(raw: string): string | null {
		const v = raw.normalize('NFKD').toLowerCase().replace(/\s+/g, ' ').replace(/[^\p{L} ]+/gu, '').replace(/ +/g, ' ').trim();
		return v ? `nm:${v}` : null;
	},
	placeComponent(raw: string | undefined): string {
		return (raw ?? '').replace(/:/g, ' ').trim().toLowerCase().replace(/\s+/g, ' ');
	},
	place(country: string | undefined, state: string | undefined, city: string | undefined, lat: number, lon: number) {
		const cc = spork.placeComponent(country), st = spork.placeComponent(state), ct = spork.placeComponent(city);
		const located = Number.isFinite(lat) && Number.isFinite(lon) && !(lat === 0 && lon === 0);
		const cell = located ? geohash(lat, lon, 7) : null;
		const key = `pl:${cc}:${st}:${ct}${cell ? `:${cell}` : ''}`;
		return { key, placeId: placeId(key), cityNorm: ct, geohash7: cell };
	},
	textHash(title: string, body: string): string {
		const t = Buffer.from(title, 'utf8');
		const len = Buffer.alloc(4);
		len.writeUInt32BE(t.length);
		return createHash('sha256').update(len).update(t).update(Buffer.from(body, 'utf8')).digest('hex');
	},
	upstreamRid(rid: string): string | null {
		const m = /^#?(\d+):(\d+)$/.exec(rid);
		if (!m) return null;
		const cluster = BigInt(m[1]), pos = BigInt(m[2]);
		if (cluster >= 1n << 23n || pos >= 1n << 40n) return null;
		return ((cluster << 40n) | pos).toString();
	},
};
const PLACE_TO_AREA: Record<string, string> = (() => {
	const m: Record<string, string> = {};
	for (const [area, place] of Object.entries(geography.AREA_CODE_TO_PLACE as Record<string, string>)) if (!m[place]) m[place] = area;
	return m;
})();
const B32 = '0123456789bcdefghjkmnpqrstuvwxyz';
function geohash(lat: number, lon: number, precision: number): string {
	let latLo = -90, latHi = 90, lonLo = -180, lonHi = 180, even = true, bit = 0, ch = 0, out = '';
	while (out.length < precision) {
		if (even) {
			const mid = (lonLo + lonHi) / 2;
			if (lon >= mid) { ch = (ch << 1) | 1; lonLo = mid; } else { ch <<= 1; lonHi = mid; }
		} else {
			const mid = (latLo + latHi) / 2;
			if (lat >= mid) { ch = (ch << 1) | 1; latLo = mid; } else { ch <<= 1; latHi = mid; }
		}
		even = !even;
		if (++bit === 5) { out += B32[ch]; bit = 0; ch = 0; }
	}
	return out;
}
function placeId(key: string): string {
	const hex = createHash('sha256').update(key, 'utf8').digest('hex').slice(0, 16);
	return BigInt.asIntN(64, BigInt(`0x${hex}`)).toString();
}

// ── Inputs ───────────────────────────────────────────────────────────────────────────────────────────────────────────
const PLACES: ({ city: string; state: string } | null)[] = [
	null, { city: 'Houston', state: 'TX' }, { city: 'Dallas', state: 'TX' }, { city: 'Austin', state: 'TX' },
	{ city: 'houston', state: 'tx' }, { city: 'Boise', state: 'ID' }, { city: 'Los Angeles', state: 'CA' },
];
const PHONES = [
	'(713) 555-0142', '+1 713-555-0142', '713.555.0142', '7135550142', '17135550142', '1-713-555-0142', '+1 (713) 555-0142\u00a0',
	'\u00a0713-555-0142\u2003', '555-0142', '5550142', ' 555 0142 ', '21234567890', '11234567890', '12345678', '123456789', '123456',
	'123', '', '   ', 'abc', 'call me', '1-800-FLOWERS', '713 555 0142 ext 99', '(713)5550142', '1 (832) 555 1234', '+44 20 7946 0958',
	'+52 1 55 1234 5678', '٧١٣٥٥٥٠١٤٢', '７１３５５５０１４２',
	'713-555-0142, 832-555-9999', '#7135550142', '1.713.555.0142', '(281) 555-0000', '0000000', '1234567', '+1 555 0142',
	'1 555 0142', '15550142', '(555) 0142', '713-555-014', 'Text 713 555 0142!!', '\t7135550142\n', '713‑555‑0142',
];
const EMAILS = [
	'Jane.Doe@Example.COM', 'jane.doe@example.com', ' JANE@x.com ', '@jane@example.com', 'MIXED+Tag@Sub.Domain.Org', '', '  ',
	'\u00a0Jane@Example.com\u00a0', 'İSTANBUL@örnek.com', 'ΟΔΟΣ@mail.gr', 'STRASSE@ß.de', 'a@b', 'no-at-sign.com', 'ǅemal@x.ba',
];
const SOCIALS: { platform?: string; handle: string }[] = [
	{ platform: 'instagram', handle: 'JaneDoe' }, { platform: 'Instagram', handle: '@JaneDoe' }, { platform: 'snapchat', handle: ' jane_doe ' },
	{ platform: 'twitter', handle: '@@double' }, { platform: '', handle: 'bare' }, { handle: 'NoPlatform' }, { platform: 'onlyfans', handle: '' },
	{ platform: 'TikTok', handle: 'Ünïcode.Name' }, { platform: 'telegram', handle: '\u00a0@Spaced\u00a0' }, { platform: 'kik', handle: 'İz' },
];
const BUSINESSES = [
	'Velvet Lounge', 'VELVET-LOUNGE!', 'velvet lounge llc', '  Spa 24/7  ', 'Café Olé', 'ÆON Spa', '', '***', 'Spa 2', 'Spa  2', 'İnci Spa',
	'Massage & Spa, Inc.', '\u00a0Night Owl\u00a0',
];
const NAMES = [
	'José García', 'JOSE GARCIA', '  jose   garcia  ', "O'Brien", 'Mary-Jane Watson', 'Zoë Saldaña', 'Ǆenan', 'ﬁona', 'Ⅻ Smith', 'Smith 3rd',
	'Łukasz Żółć', 'Øyvind', 'straße', 'Иван Петров', '李小龙', 'محمد', '', '123', 'İlkay', 'Ångström\tOne\nTwo',
];
const IMAGE_HASHES = [
	'9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08', '9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08',
	'd41d8cd98f00b204e9800998ecf8427e', ' 9f86d081 ', 'abc', '', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85',
];
const STYLE_TEXTS = [
	'', 'Hello', 'NEW in town!!! Call me now... available 24/7 for you', 'the the the a a and', "i'm new… text me? only $200!!",
	'Sweet & SEXY 😘 call/text 713-555-0142', 'No rush… …slow and sensual... ... ?', 'ALL CAPS AD!!!', "Don't miss out, I'm available now",
	'Ünïcödé wörds and the new girl', 'a\u00a0the\tto\nof', '?!?!?!', '...', '……', 'The. The? THE! the', "it's you and me, i promise",
];
const TEXTS: { title: string; body: string }[] = [
	{ title: '', body: '' }, { title: 'Hi', body: 'there' }, { title: 'Hit', body: 'here' }, { title: 'Hithere', body: '' },
	{ title: 'NEW in town', body: 'Call me now... 😘' }, { title: 'Ünïcödé', body: 'body\r\nwith CRLF ' }, { title: 'x'.repeat(300), body: 'y' },
];
const RIDS = ['168:8', '#168:8', '0:0', '8388607:1099511627775', '8388608:0', '1:1099511627776', '12:34:56', 'abc', '', '-1:5', '#12:'];
const GEO_RECORDS: { basic: Record<string, unknown>; rec: Record<string, unknown> }[] = [
	{ basic: { lat: 29.7604, lon: -95.3698 }, rec: { Location: [{ city: 'Houston', state: 'TX', country: 'US', geocode: [29.7, -95.3] }] } },
	{ basic: {}, rec: { Location: [{ city: 'Houston', state: 'TX', country: 'US', geocode: [29.7, -95.3] }] } },
	{ basic: {}, rec: { Location: [{ city: 'Houston', state: 'TX' }] } },
	{ basic: { lat: 0, lon: 0 }, rec: { Location: [{ city: 'new york', state: 'NY' }] } },
	{ basic: {}, rec: { Location: [{ city: 'Boise', state: 'ID', country: 'US' }] } },
	{ basic: {}, rec: { Location: [{}] } },
	{ basic: { lat: '29.7', lon: '-95.3' }, rec: { Location: [{ city: 'Dallas', state: 'TX', geocode: [32.7767, -96.797] }] } },
	{ basic: {}, rec: { Location: [{ city: 'São Paulo', state: 'SP', country: 'BR', geocode: [-23.5505, -46.6333] }] } },
	{ basic: {}, rec: { Location: [{ city: '  Las   Vegas ', state: 'nv', country: 'us', geocode: [36.1699, -115.1398] }] } },
	{ basic: {}, rec: { Location: [{ city: 'Ciudad: Juárez', state: 'CHH', country: 'MX', geocode: [31.6904, -106.4245] }] } },
];
const SOURCE_TOKENS = [
	'168:8', '7135550142', '(713) 555-0142', 'jane@example.com', '@janedoe', 'janedoe', 'jane@doe', '555-0142', '555 01', 'instagram:jane',
	'+1 713 555 0142', '713-555-0142 ext', '', '  ', '1234567890123',
];

// ── Records ──────────────────────────────────────────────────────────────────────────────────────────────────────────
const placeOrNull = (p: { city: string; state: string } | null) => (p ? { city: p.city, state: p.state } : null);
const phones = PHONES.flatMap((raw) => PLACES.map((place) => {
	const r = place ? { city: place.city, state: place.state } : undefined;
	return {
		input: raw,
		place: placeOrNull(place),
		recon: {
			engineCanonPhone: engine.canonPhone(raw, r),
			engineAreaCodeOf: engine.areaCodeOf(raw, r),
			engineClusterKey: engine.clusterKey('phone', raw, r ?? { city: '', state: '' }),
			engineNormPhone: engine.canonPhone(raw),
			compareNormPhone: compare.normPhone(raw),
			boardNormalizeAnchor: board.normalizeAnchor('phone', raw),
			dossierIdentKey: dossier.identKey('phone', raw),
			feedPhoneKey: feed.phoneKey(raw),
			auditIntakeSubject: audit.intakeSubject([raw]),
			classifySource: engineMod.classifySource(raw),
		},
		spork: spork.phone(raw, place ?? undefined),
	};
}));
const emails = EMAILS.map((raw) => ({
	input: raw,
	recon: {
		engineLinkKey: engineLinkLower(raw),
		engineClusterKey: engine.clusterKey('email', raw, {}),
		boardNormalizeAnchor: board.normalizeAnchor('email', raw),
		dossierIdentKey: dossier.identKey('email', raw),
		auditIntakeSubject: audit.intakeSubject([raw]),
		classifySource: engineMod.classifySource(raw),
	},
	spork: { key: spork.email(raw) },
}));
const socials = SOCIALS.map((s) => {
	const joined = `${s.platform}:${s.handle}`;
	return {
		platform: s.platform ?? null,
		handle: s.handle,
		recon: {
			engineLinkKey: engineLinkSocial(String(s.platform), s.handle),
			boardNormalizeAnchorHandle: board.normalizeAnchor('social', s.handle),
			dossierIdentKey: dossier.identKey('social', joined),
		},
		spork: { key: spork.handle(s.platform, s.handle) },
	};
});
const businesses = BUSINESSES.map((raw) => ({
	input: raw,
	recon: {
		engineLinkKey: engineLinkLower(raw),
		engineClusterKey: engine.clusterKey('businessName', raw, {}),
		boardNormalizeAnchor: board.normalizeAnchor('businessName', raw),
		dossierIdentKey: dossier.identKey('businessName', raw),
	},
	spork: { key: spork.business(raw) },
}));
const names = NAMES.map((raw) => ({ input: raw, spork: { key: spork.name(raw) } }));
const imageHashes = IMAGE_HASHES.map((raw) => ({
	input: raw,
	recon: { engineLinkKey: raw, dossierIdentKey: dossier.identKey('imageHash', raw) },
	spork: { key: spork.imageHash(raw) },
}));
const style = STYLE_TEXTS.map((text) => ({
	input: text,
	recon: { styleVector: identityMod.styleVector(text), fingerprint: identityMod.stylometricFingerprint(text) },
}));
const textHashes = TEXTS.map((t) => ({ title: t.title, body: t.body, spork: { textHash: spork.textHash(t.title, t.body) } }));
const rids = RIDS.map((rid) => ({ input: rid, spork: { upstreamRid: spork.upstreamRid(rid) } }));
const geo = GEO_RECORDS.map((g) => {
	const r = apiSource.mapRecord('1:1', g.basic, g.rec);
	return {
		basic: g.basic,
		location: (g.rec.Location as unknown[])[0],
		recon: { city: r.city, state: r.state, country: r.country, lat: r.lat, lon: r.lon },
		spork: spork.place(r.country, r.state, r.city, r.lat, r.lon),
	};
});
const records = [
	{
		Phone: [{ phone_number: '(713) 555-0142' }, { phone_number_original: '713.555.0143' }, { number: '5550144' }, { phone: '' },
			{ area_code: '832', line: '555-0145' }, {}],
		Email: [{ email_address: 'A@B.com' }, { email: 'c@d.org' }, { address: 'e@f.net' }, {}],
		Social: [{ social_platform: 'instagram', username: '@Jane' }, { platform: 'snapchat', handle: 'jd' }, { value: 'bare' }, { platform: 'x' }],
		Business: [{ business_name: 'Velvet Lounge' }, { name: 'Night Owl' }, {}],
		File: [{ mime_type: 'image/jpeg', hash: 'AbC123' }, { mime_type: 'meta_path', hash: 'skip' }, { sha256: 'deadbeef' }, { mime_type: 'image/png' }],
		Location: [{ city: 'Houston', state: 'TX' }],
	},
].map((rec) => {
	const r = apiSource.mapRecord('168:8', {}, rec);
	return {
		rec,
		recon: { phones: r.phones, emails: r.emails, socials: r.socials, businessNames: r.businessNames, imageHashes: r.imageHashes },
	};
});
const sourceTokens = SOURCE_TOKENS.map((t) => ({ input: t, recon: { classifySource: engineMod.classifySource(t) } }));

// ── Write ────────────────────────────────────────────────────────────────────────────────────────────────────────────
const reconHead = (() => {
	try {
		return Bun.spawnSync(['git', '-C', RECON, 'rev-parse', 'HEAD']).stdout.toString().trim();
	} catch {
		return 'unknown';
	}
})();
mkdirSync(OUT, { recursive: true });
const write = (file: string, cases: unknown[]) =>
	writeFileSync(join(OUT, file), `${JSON.stringify({ generator: 'spork-keys/golden/generate-golden.ts', reconCommit: reconHead, cases }, null, 1)}\n`);
write('phones.json', phones);
write('emails.json', emails);
write('handles.json', socials);
write('businesses.json', businesses);
write('names.json', names);
write('image-hashes.json', imageHashes);
write('style.json', style);
write('text-hashes.json', textHashes);
write('upstream-rids.json', rids);
write('places.json', geo);
write('records.json', records);
write('source-tokens.json', sourceTokens);
write('area-codes.json', [{ areaCodeToPlace: geography.AREA_CODE_TO_PLACE, placeToArea: PLACE_TO_AREA }]);
console.log(`wrote ${OUT} from Recon ${reconHead}`);
process.exit(0);
