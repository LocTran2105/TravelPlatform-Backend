#!/usr/bin/env node
/**
 * Seed partner demo data through the real business flow (no direct SQL):
 *   register -> login -> partner application -> admin approves
 *   -> partner creates business (+hours, notes) -> submit
 *   -> admin checks OSM candidates -> approve as OSM link or PARTNER place.
 *
 * Usage (from this folder):
 *   node seed-partners.mjs --dry-run                 validate data + compare with OpenStreetMap, no backend calls
 *   node seed-partners.mjs                           run everything (idempotent, safe to re-run)
 *   node seed-partners.mjs --only=dlcf01,dlcf02      run selected keys
 *   node seed-partners.mjs --stop-before-place-approval   leave place submissions PENDING for manual review
 *   node seed-partners.mjs --data=other.json         use another data file
 *   node seed-partners.mjs --images                  upload new files from images/<key>/, resubmit, re-approve same place
 *
 * Env:
 *   ADMIN_EMAIL, ADMIN_PASSWORD   existing ROLE_ADMIN account (required unless --dry-run)
 *   SEED_PASSWORD                 password for all seeded partner accounts (default: Partner@2026)
 *   API_BASE                      default http://localhost:8080
 *
 * Requires Node 18+ (global fetch).
 */
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { basename, dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...v] = a.replace(/^--/, '').split('=');
    return [k, v.length ? v.join('=') : true];
  }),
);
const DRY_RUN = Boolean(args['dry-run']);
const STOP_BEFORE_PLACE = Boolean(args['stop-before-place-approval']);
const IMAGES_MODE = Boolean(args.images);
const ONLY = typeof args.only === 'string' ? new Set(args.only.split(',').map((s) => s.trim())) : null;
const DATA_FILE = resolve(HERE, typeof args.data === 'string' ? args.data : 'cafes-dalat.json');
const STATE_FILE = join(HERE, 'state.json');
const REPORT_FILE = join(HERE, 'report.md');
// Written by --dry-run (one per data file); used when the backend cannot reach Overpass during approval
const OSM_CACHE_FILE = join(HERE, `osm-cache-${basename(DATA_FILE, '.json')}.json`);
const IMAGES_DIR = join(HERE, 'images'); // images/<key>/1.jpg, 2.jpg ... (first one becomes the cover)

const API_BASE = (process.env.API_BASE || 'http://localhost:8080').replace(/\/$/, '');
const SEED_PASSWORD = process.env.SEED_PASSWORD || 'Partner@2026';
const OVERPASS_URL = 'https://overpass-api.de/api/interpreter';
const USER_AGENT = 'travel-platform-seed-script/1.0';

const MATCH_MAX_METERS = 300; // OSM candidate with matching name within this distance => link OSM
const CANDIDATE_RADIUS = 500;
const DALAT_BOX = { minLat: 11.8, maxLat: 12.1, minLng: 108.3, maxLng: 108.6 };
const TAX_RE = /^(\d{10}(-\d{3})?|\d{12})$/;
const PHONE_RE = /^(0|\+84)\d{9,10}$/;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
// Allowed by both the Java validator and the SQL CHECK constraint on partner_businesses.business_type
const SAFE_BUSINESS_TYPES = ['CAFE', 'RESTAURANT', 'HOTEL', 'HOMESTAY', 'RESORT', 'ATTRACTION', 'OTHER'];

// ---------------------------------------------------------------- helpers
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (key, msg) => console.log(`[${key}] ${msg}`);

function normalizeName(s) {
  return String(s || '')
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/đ/gi, 'd')
    .toLowerCase()
    .replace(/[^a-z0-9 ]+/g, ' ')
    .replace(/\b(tiem|quan an|quan|ca phe|caphe|cafe|coffee|coffe|nha hang|restaurant|khach san|hotel|homestay|resort|the|da lat|dalat)\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function namesMatch(a, b) {
  const x = normalizeName(a);
  const y = normalizeName(b);
  if (x.length < 3 || y.length < 3) return false;
  const [short, long] = x.length <= y.length ? [x, y] : [y, x];
  // "tung" must not match "goc cua tung": the shorter name has to cover at least half of the longer one
  return long.includes(short) && short.length / long.length >= 0.5;
}

function distanceMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return Math.round(2 * R * Math.asin(Math.sqrt(h)));
}

const gmapsLink = (c) => `https://www.google.com/maps?q=${c.latitude},${c.longitude}`;
const osmLink = (c) => `https://www.openstreetmap.org/?mlat=${c.latitude}&mlon=${c.longitude}#map=19/${c.latitude}/${c.longitude}`;

// ---------------------------------------------------------------- data
function buildRecords(data) {
  const d = data.defaults || {};
  // demoSeries (2 digits) keeps demo tax codes / phones unique across data files:
  // series "00" -> 58000001NN / 0900000 1NN, series "01" -> 58010001NN / 0900010 1NN ...
  const series = String(d.demoSeries ?? '00');
  return (data.places || data.cafes).map((c, i) => {
    const nn = String(i + 1).padStart(2, '0');
    const email = `${c.key}@${d.emailDomain || 'example.com'}`;
    const demoPhone = c.contactPhone || `0900${series}01${nn}`;
    const city = c.city || d.city;
    const province = c.province || d.province;
    const fullAddress = [c.address, c.ward, city, province].filter(Boolean).join(', ');
    return {
      ...c,
      city,
      province,
      email,
      account: { fullName: `Đại diện ${c.name}`, email, password: SEED_PASSWORD },
      application: {
        displayName: c.name,
        legalName: `Hộ kinh doanh ${c.name} (dữ liệu demo)`,
        taxCode: c.taxCode || `58${series}0001${nn}`,
        businessAddress: fullAddress,
        contactPerson: `Đại diện ${c.name}`,
        contactPhone: demoPhone,
        contactEmail: email,
        description: `${c.shortDescription} ${c.description}`.slice(0, 2000),
      },
      business: {
        name: c.name,
        businessType: c.businessType || d.businessType || 'CAFE',
        priceLevel: c.priceLevel ?? d.priceLevel ?? null,
        shortDescription: c.shortDescription,
        description: c.description,
        address: c.address,
        ward: c.ward || null,
        district: null,
        city,
        province,
        countryCode: 'VN',
        currency: 'VND',
        latitude: c.latitude,
        longitude: c.longitude,
        phoneNumber: demoPhone,
        email,
      },
      hoursPayload: [1, 2, 3, 4, 5, 6, 7].map((day) => ({
        dayOfWeek: day,
        openTime: c.hours.open,
        closeTime: c.hours.close,
        closed: (c.hours.closedDays || []).includes(day),
      })),
      notes: [
        c.priceNote && { title: 'Giá tham khảo', content: c.priceNote, noteType: 'PRICE_INFO', active: true },
        c.tip && { title: 'Mẹo khi ghé quán', content: c.tip, noteType: 'TRAVEL_TIP', active: true },
      ].filter(Boolean),
    };
  });
}

function validate(records) {
  const errors = [];
  const seen = { email: new Set(), tax: new Set(), key: new Set() };
  const box = records.bbox || DALAT_BOX;
  for (const r of records) {
    const e = (m) => errors.push(`[${r.key}] ${m}`);
    if (!/^[a-z0-9]{3,9}$/.test(r.key)) e('key phải là chữ thường/số, 3-9 ký tự (dùng làm username email)');
    for (const [set, v, label] of [[seen.key, r.key, 'key'], [seen.email, r.email, 'email'], [seen.tax, r.application.taxCode, 'taxCode']]) {
      if (set.has(v)) e(`${label} bị trùng: ${v}`);
      set.add(v);
    }
    if (!r.name?.trim()) e('thiếu name');
    if (!r.address?.trim()) e('thiếu address');
    if (!TAX_RE.test(r.application.taxCode)) e(`taxCode sai định dạng: ${r.application.taxCode}`);
    if (!PHONE_RE.test(r.application.contactPhone)) e(`contactPhone sai định dạng: ${r.application.contactPhone}`);
    const { latitude: lat, longitude: lng } = r;
    if (typeof lat !== 'number' || typeof lng !== 'number') e('latitude/longitude phải là số');
    else if (lat < box.minLat || lat > box.maxLat || lng < box.minLng || lng > box.maxLng)
      e(`tọa độ ${lat},${lng} nằm ngoài vùng cho phép (defaults.bbox)`);
    if (!SAFE_BUSINESS_TYPES.includes(r.business.businessType))
      e(`businessType "${r.business.businessType}" không hợp lệ, chỉ dùng: ${SAFE_BUSINESS_TYPES.join(', ')}`);
    const { open, close } = r.hours || {};
    if (!TIME_RE.test(open || '') || !TIME_RE.test(close || '')) e('hours.open/close phải dạng HH:mm');
    else if (close <= open) e('giờ đóng cửa phải sau giờ mở cửa (backend không hỗ trợ qua đêm)');
    if (r.application.businessAddress.length > 500) e('địa chỉ quá 500 ký tự');
    if ((r.shortDescription || '').length > 500) e('shortDescription quá 500 ký tự');
  }
  return errors;
}

// ---------------------------------------------------------------- OSM check (dry run, public Overpass)
// Same OSM tags the backend uses (OsmPlaceDiscoveryService.buildOverpassQuery / matchesOsmCategory)
const OSM_SELECTORS = {
  CAFE: ['["amenity"="cafe"]'],
  RESTAURANT: ['["amenity"~"^(restaurant|food_court|fast_food|bbq)$"]'],
  ACCOMMODATION: ['["tourism"~"^(hotel|guest_house|hostel|chalet|resort|motel|apartment)$"]'],
  ATTRACTION: [
    '["tourism"~"^(attraction|viewpoint|museum|theme_park|gallery|zoo|aquarium|artwork)$"]',
    '["attraction"]',
    '["historic"~"^(monument|memorial|castle|ruins|archaeological_site)$"]',
    '["leisure"~"^(park|garden|nature_reserve)$"]',
    '["natural"~"^(waterfall|peak|cave|beach|hot_spring)$"]',
  ],
};

/** businessType -> backend place category; null = backend does not search OSM for this type. */
function osmCategory(businessType) {
  switch (String(businessType || '').toUpperCase()) {
    case 'CAFE': case 'COFFEE_SHOP': return 'CAFE';
    case 'RESTAURANT': case 'EATERY': return 'RESTAURANT';
    case 'HOTEL': case 'HOMESTAY': case 'RESORT': case 'HOSTEL': case 'APARTMENT': return 'ACCOMMODATION';
    case 'ATTRACTION': case 'TOURIST_SITE': return 'ATTRACTION';
    default: return null;
  }
}

function tagCategory(t = {}) {
  const amenity = t.amenity || '';
  if (amenity === 'cafe') return 'CAFE';
  if (['restaurant', 'food_court', 'fast_food', 'bbq'].includes(amenity)) return 'RESTAURANT';
  if (['hotel', 'guest_house', 'hostel', 'chalet', 'resort', 'motel', 'apartment'].includes(t.tourism)) return 'ACCOMMODATION';
  if (['attraction', 'viewpoint', 'museum', 'theme_park', 'gallery', 'zoo', 'aquarium', 'artwork'].includes(t.tourism)
    || t.attraction || t.historic || ['park', 'garden', 'nature_reserve'].includes(t.leisure)
    || ['waterfall', 'peak', 'cave', 'beach', 'hot_spring'].includes(t.natural)) return 'ATTRACTION';
  return null;
}

async function fetchOsmPlaces(records) {
  const lats = records.map((r) => r.latitude);
  const lngs = records.map((r) => r.longitude);
  const pad = 0.01;
  const bbox = [Math.min(...lats) - pad, Math.min(...lngs) - pad, Math.max(...lats) + pad, Math.max(...lngs) + pad].join(',');
  const categories = [...new Set(records.map((r) => osmCategory(r.business.businessType)).filter(Boolean))];
  if (!categories.length) return [];
  const selectors = categories.flatMap((c) => OSM_SELECTORS[c]).map((sel) => `nwr${sel}["name"](${bbox});`).join('');
  const q = `[out:json][timeout:90];(${selectors});out center tags;`;
  let json;
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(OVERPASS_URL, {
        method: 'POST',
        headers: { 'User-Agent': USER_AGENT, 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ data: q }),
        signal: AbortSignal.timeout(90000),
      });
      if (!res.ok) throw new Error(`Overpass HTTP ${res.status}`);
      json = await res.json();
      break;
    } catch (err) {
      if (attempt >= 3) throw err;
      console.log(`${err.message}, thử lại sau ${attempt * 10}s...`);
      await sleep(attempt * 10000);
    }
  }
  return json.elements.map((el) => ({
    ref: `${el.type}/${el.id}`,
    name: el.tags.name,
    category: tagCategory(el.tags),
    latitude: el.lat ?? el.center?.lat,
    longitude: el.lon ?? el.center?.lon,
  }));
}

function compareWithOsm(record, osmPlaces) {
  const category = osmCategory(record.business.businessType);
  const withDist = osmPlaces
    // entries without a category come from the old cafe-only cache
    .filter((o) => category && (o.category || 'CAFE') === category)
    .map((o) => ({ ...o, distanceMeters: distanceMeters(record.latitude, record.longitude, o.latitude, o.longitude) }))
    .sort((a, b) => a.distanceMeters - b.distanceMeters);
  const nameMatch = withDist.find((o) => namesMatch(record.name, o.name));
  const nearest = withDist[0];
  return { nameMatch, nearest };
}

// ---------------------------------------------------------------- API client
class ApiError extends Error {
  constructor(status, body, step) {
    super(`${step}: HTTP ${status} – ${typeof body === 'string' ? body : JSON.stringify(body)}`);
    this.status = status;
    this.body = body;
  }
}

async function api(step, method, path, { token, body } = {}) {
  const res = await fetch(API_BASE + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(60000),
  });
  const text = await res.text();
  let data = text;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    /* plain text response */
  }
  if (!res.ok) throw new ApiError(res.status, data, step);
  return data;
}

const asList = (data) => (Array.isArray(data) ? data : Array.isArray(data?.content) ? data.content : []);

async function login(email, password) {
  const data = await api('login', 'POST', '/api/auth/login', { body: { email, password } });
  const token = data?.token || data?.accessToken;
  if (!token) throw new Error(`login ${email}: không nhận được token`);
  return { token, id: data.id, role: data.role };
}

// ---------------------------------------------------------------- flow steps
async function ensureAccount(r) {
  try {
    await api('register', 'POST', '/api/auth/register', {
      body: { fullName: r.account.fullName, email: r.email, password: r.account.password },
    });
    log(r.key, `đã đăng ký ${r.email}`);
  } catch (err) {
    if (err.status === 400 && /Email đã được sử dụng/i.test(String(err.body))) log(r.key, `tài khoản đã tồn tại, bỏ qua đăng ký`);
    else throw err;
  }
  return login(r.email, r.account.password);
}

async function ensureApplication(r, partnerToken, adminToken, state) {
  let apps = [];
  try {
    apps = asList(await api('my-applications', 'GET', '/api/partner-applications/my-applications', { token: partnerToken }));
  } catch (err) {
    if (err.status !== 404) throw err;
  }
  const status = (a) => String(a.verificationStatus || a.status || '').toUpperCase();
  if (apps.some((a) => status(a) === 'APPROVED')) {
    log(r.key, 'hồ sơ đối tác đã được duyệt, bỏ qua');
    return;
  }
  let pending = apps.find((a) => status(a) === 'PENDING');
  if (!pending) {
    pending = await api('apply', 'POST', '/api/partner-applications', { token: partnerToken, body: r.application });
    log(r.key, `đã nộp hồ sơ đối tác #${pending.id}`);
  } else log(r.key, `hồ sơ #${pending.id} đang chờ duyệt`);
  state.applicationId = pending.id;

  await api('admin approve application', 'PUT', `/api/admin/partner-applications/${pending.id}/approve`, {
    token: adminToken,
    body: { note: 'Seed demo – hồ sơ hợp lệ' },
  });
  log(r.key, `admin đã duyệt hồ sơ #${pending.id} -> ROLE_PARTNER`);
}

async function ensureBusiness(r, partnerToken, state) {
  const mine = asList(await api('my businesses', 'GET', '/api/partner/businesses/mine', { token: partnerToken }));
  let biz = mine.find((b) => b.id === state.businessId) || mine.find((b) => (b.name || '').trim() === r.name.trim());
  if (!biz) {
    biz = await api('create business', 'POST', '/api/partner/businesses', { token: partnerToken, body: r.business });
    log(r.key, `đã tạo địa điểm #${biz.id} (${biz.slug || r.name})`);
  } else log(r.key, `địa điểm #${biz.id} đã có, trạng thái ${biz.verificationStatus}`);
  state.businessId = biz.id;

  const vs = String(biz.verificationStatus || '').toUpperCase();
  if (vs === 'DRAFT' || vs === 'REJECTED') {
    await api('set hours', 'PUT', `/api/partner/businesses/${biz.id}/hours`, { token: partnerToken, body: r.hoursPayload });
    const existing = asList(await api('get notes', 'GET', `/api/partner/businesses/${biz.id}/notes`, { token: partnerToken }));
    const titles = new Set(existing.map((n) => n.title));
    for (const note of r.notes.filter((n) => !titles.has(n.title))) {
      await api('add note', 'POST', `/api/partner/businesses/${biz.id}/notes`, { token: partnerToken, body: note });
    }
    log(r.key, `đã cập nhật giờ mở cửa ${r.hours.open}-${r.hours.close} và ${r.notes.length} ghi chú`);
    // Images already in images/<key>/ go in before the first submit, so the admin reviews only once.
    await uploadNewImages(r, partnerToken, biz.id, state);

    const sub = await api('submit', 'POST', `/api/partner/businesses/${biz.id}/submit`, { token: partnerToken });
    state.submissionId = sub.id;
    log(r.key, `đã gửi xét duyệt, submission #${sub.id}`);
  }
  return vs;
}

/** Upload files from images/<key>/ that were not uploaded before; first image becomes the cover. */
async function uploadNewImages(r, token, businessId, state) {
  state.media ||= {};
  const newFiles = (await listImages(r.key)).filter((f) => !state.media[f.name]);
  if (!newFiles.length) return 0;
  const existing = asList(await api('get media', 'GET', `/api/partner/businesses/${businessId}/media`, { token }));
  let order = existing.length;
  let needCover = !existing.some((m) => m.cover);
  for (const f of newFiles) {
    const url = await uploadImage(f, token);
    await api('add media', 'POST', `/api/partner/businesses/${businessId}/media`, {
      token,
      body: { mediaType: 'IMAGE', mediaUrl: url, thumbnailUrl: null, caption: `${r.name} – ảnh ${order + 1}`, cover: needCover, sortOrder: order },
    });
    state.media[f.name] = url;
    log(r.key, `đã tải ${f.name}${needCover ? ' (ảnh bìa)' : ''}`);
    needCover = false;
    order += 1;
  }
  return newFiles.length;
}

async function getOsmCandidates(submissionId, key) {
  const delays = [10000, 20000, 40000];
  for (let attempt = 0; ; attempt++) {
    try {
      return asList(await api('osm candidates', 'GET', `/api/admin/partner-submissions/${submissionId}/osm-candidates?radiusMeters=${CANDIDATE_RADIUS}`, { token: globalThis.ADMIN_TOKEN }));
    } catch (err) {
      if (attempt >= delays.length || err.status !== 400) throw err;
      log(key, `Overpass lỗi (${String(err.body).slice(0, 80)}), thử lại sau ${delays[attempt] / 1000}s`);
      await sleep(delays[attempt]);
    }
  }
}

async function ensurePlaceApproval(r, adminToken, state) {
  const pending = asList(await api('pending submissions', 'GET', '/api/admin/partner-submissions?status=PENDING', { token: adminToken }));
  const sub = pending.find((s) => s.businessId === state.businessId);
  if (!sub) {
    log(r.key, 'không có submission PENDING (đã duyệt trước đó?)');
    return;
  }
  state.submissionId = sub.id;
  if (STOP_BEFORE_PLACE) {
    state.decision = 'PENDING (manual)';
    log(r.key, `để submission #${sub.id} chờ admin duyệt tay`);
    return;
  }

  let candidates;
  try {
    candidates = await getOsmCandidates(sub.id, r.key);
  } catch (err) {
    log(r.key, `không lấy được ứng viên OSM từ backend: ${String(err.body || err.message).slice(0, 120)}`);
    // Fallback: if the cached OSM snapshot has no café with this name nearby, a PARTNER place is safe to create.
    // If it does, linking needs the backend's candidate id, so leave the submission PENDING for a later run.
    if (!existsSync(OSM_CACHE_FILE)) {
      state.decision = 'PENDING (Overpass lỗi, chưa có osm-cafes-cache.json – chạy --dry-run hoặc chạy lại sau)';
      return;
    }
    const cache = JSON.parse(await readFile(OSM_CACHE_FILE, 'utf8'));
    const { nameMatch } = compareWithOsm(r, cache.places || cache.cafes);
    if (nameMatch && nameMatch.distanceMeters <= MATCH_MAX_METERS) {
      state.decision = `PENDING (OSM có "${nameMatch.name}" cách ${nameMatch.distanceMeters} m – chạy lại khi Overpass ổn để liên kết)`;
      log(r.key, state.decision);
      return;
    }
    const fetched = String(cache.fetchedAt).slice(0, 10);
    await api('approve submission', 'PUT', `/api/admin/partner-submissions/${sub.id}/approve`, {
      token: adminToken,
      body: { note: `Seed demo – không có trên OSM (đối chiếu bản OSM ngày ${fetched}), tạo Place theo tọa độ Google Maps`, placeProvider: 'PARTNER', placeId: null, latitude: r.latitude, longitude: r.longitude },
    });
    Object.assign(state, { decision: 'PARTNER', osmRef: null, osmName: null, osmPlaceId: null, distanceMeters: null, osmCheck: `cache ${fetched}` });
    log(r.key, `duyệt: tạo Place PARTNER tại ${r.latitude},${r.longitude} (bản OSM lưu ${fetched} không có quán này)`);
    return;
  }
  const match = candidates
    .filter((c) => namesMatch(r.name, c.name) && Number(c.distanceMeters) <= MATCH_MAX_METERS)
    .sort((a, b) => a.distanceMeters - b.distanceMeters)[0];

  const body = match
    ? { note: `Seed demo – khớp OSM ${match.providerPlaceId} (${match.name}, cách ${Math.round(match.distanceMeters)} m)`, placeProvider: 'OSM', placeId: match.id, latitude: null, longitude: null }
    : { note: 'Seed demo – không có trên OSM, tạo Place theo tọa độ Google Maps', placeProvider: 'PARTNER', placeId: null, latitude: r.latitude, longitude: r.longitude };

  await api('approve submission', 'PUT', `/api/admin/partner-submissions/${sub.id}/approve`, { token: adminToken, body });
  Object.assign(state, {
    decision: body.placeProvider,
    osmRef: match?.providerPlaceId || null,
    osmName: match?.name || null,
    osmPlaceId: match?.id || null,
    distanceMeters: match ? Math.round(match.distanceMeters) : null,
    candidatesSeen: candidates.length,
  });
  log(r.key, match ? `duyệt: liên kết OSM ${match.providerPlaceId} "${match.name}" (${Math.round(match.distanceMeters)} m)` : `duyệt: tạo Place PARTNER tại ${r.latitude},${r.longitude} (${candidates.length} ứng viên OSM, không khớp)`);
}

// ---------------------------------------------------------------- images (--images)
// Adding media sends an APPROVED business back to DRAFT/DRAFT (hidden) until it is resubmitted and
// re-approved, so each café is handled end to end: upload -> submit -> admin re-approve with the SAME place.
const IMAGE_TYPES = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif' };
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

async function listImages(key) {
  const dir = join(IMAGES_DIR, key);
  if (!existsSync(dir)) return [];
  const names = await readdir(dir);
  return names
    .filter((n) => IMAGE_TYPES[extname(n).toLowerCase()])
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    .map((n) => ({ name: n, path: join(dir, n), type: IMAGE_TYPES[extname(n).toLowerCase()] }));
}

async function uploadImage(file, token) {
  const bytes = await readFile(file.path);
  if (bytes.length > MAX_IMAGE_BYTES) throw new Error(`${file.name} lớn hơn 10 MB`);
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: file.type }), file.name);
  const res = await fetch(`${API_BASE}/api/upload/image`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
    signal: AbortSignal.timeout(120000),
  });
  const text = (await res.text()).trim();
  if (!res.ok) throw new ApiError(res.status, text, `upload ${file.name}`);
  let url = text;
  try {
    const parsed = JSON.parse(text);
    url = typeof parsed === 'string' ? parsed : parsed?.secureUrl || parsed?.imageUrl || parsed?.url;
  } catch {
    /* plain-text URL */
  }
  if (!/^https?:\/\//.test(url || '')) throw new Error(`upload ${file.name}: phản hồi không phải URL: ${text.slice(0, 120)}`);
  return url;
}

function approvalBodyFromState(state, business) {
  if (state.decision === 'OSM' && state.linkedPlaceId) {
    return { note: 'Seed demo – bổ sung ảnh, giữ liên kết OSM cũ', placeProvider: 'OSM', placeId: state.linkedPlaceId, latitude: null, longitude: null };
  }
  if (state.decision === 'PARTNER' && business.latitude != null && business.longitude != null) {
    // PARTNER approval updates the existing "partner-business/{id}" place; it does not create a second one.
    return { note: 'Seed demo – bổ sung ảnh, giữ Place PARTNER cũ', placeProvider: 'PARTNER', placeId: null, latitude: Number(business.latitude), longitude: Number(business.longitude) };
  }
  return null;
}

async function addImagesFor(r, adminToken, state) {
  if (!state.businessId) return 'BỎ QUA (chưa có địa điểm – chạy seed trước)';
  const files = await listImages(r.key);
  state.media ||= {};
  const newFiles = files.filter((f) => !state.media[f.name]);

  const adminView = asList(await api('admin business', 'GET', `/api/admin/businesses?keyword=${encodeURIComponent(r.name)}&size=20`, { token: adminToken }))
    .find((b) => b.id === state.businessId);
  if (adminView?.publicationStatus === 'HIDDEN') return 'BỎ QUA (địa điểm đang ẩn – thêm ảnh sẽ làm nó hiện lại khi duyệt)';

  const partner = await login(r.email, r.account.password);
  let biz = await api('get business', 'GET', `/api/partner/businesses/${state.businessId}`, { token: partner.token });
  const vs = String(biz.verificationStatus || '').toUpperCase();

  if (newFiles.length) {
    if (vs === 'PENDING_REVIEW') return 'BỎ QUA (đang chờ duyệt, không thêm ảnh được – duyệt xong rồi chạy lại)';
    await uploadNewImages(r, partner.token, state.businessId, state);
    biz = await api('get business', 'GET', `/api/partner/businesses/${state.businessId}`, { token: partner.token });
  } else if (vs === 'APPROVED') {
    return files.length ? 'Không có ảnh mới' : `Chưa có ảnh trong images/${r.key}/`;
  }

  if (['DRAFT', 'REJECTED'].includes(String(biz.verificationStatus || '').toUpperCase())) {
    const sub = await api('submit', 'POST', `/api/partner/businesses/${state.businessId}/submit`, { token: partner.token });
    state.submissionId = sub.id;
    log(r.key, `đã gửi duyệt lại, submission #${sub.id}`);
  }
  if (STOP_BEFORE_PLACE) return `${newFiles.length} ảnh mới, chờ admin duyệt tay`;

  const pending = asList(await api('pending submissions', 'GET', '/api/admin/partner-submissions?status=PENDING', { token: adminToken }))
    .find((s) => s.businessId === state.businessId);
  if (!pending) return `${newFiles.length} ảnh mới (không thấy submission PENDING)`;
  const body = approvalBodyFromState(state, biz);
  if (!body) return `${newFiles.length} ảnh mới, chờ admin duyệt tay (không rõ Place đang liên kết)`;
  await api('re-approve', 'PUT', `/api/admin/partner-submissions/${pending.id}/approve`, { token: adminToken, body });
  log(r.key, `admin duyệt lại #${pending.id} (${body.placeProvider}${body.placeId ? ` place#${body.placeId}` : ''})`);
  return `${newFiles.length} ảnh mới, đã duyệt lại (${body.placeProvider})`;
}

async function runImages(records, adminToken, stateAll, saveState) {
  const rows = [];
  for (const r of records) {
    const state = (stateAll[r.key] ||= {});
    let result;
    try {
      result = await addImagesFor(r, adminToken, state);
    } catch (err) {
      result = `LỖI: ${err.message}`;
      console.error(`[${r.key}] ${result}`);
    }
    await saveState();
    log(r.key, result);
    rows.push({ key: r.key, name: r.name, result, images: Object.keys(state.media || {}).length });
  }
  console.log('\nKết quả thêm ảnh');
  console.table(rows);
}

// ---------------------------------------------------------------- report
async function writeReport(rows, title) {
  const header = '| key | Tên | Kết quả | OSM | Khoảng cách | Google Maps | OSM map |\n|---|---|---|---|---|---|---|';
  const lines = rows.map(
    (x) => `| ${x.key} | ${x.name} | ${x.result} | ${x.osm || '–'} | ${x.distance ?? '–'} | [mở](${x.gmaps}) | [mở](${x.osmMap}) |`,
  );
  const md = `# ${title}\n\nTạo lúc ${new Date().toLocaleString('vi-VN')}\n\n${header}\n${lines.join('\n')}\n`;
  await writeFile(REPORT_FILE, md, 'utf8');
  console.log(`\n${title}`);
  console.table(rows.map(({ key, name, result, osm, distance }) => ({ key, name, result, osm: osm || '', 'm': distance ?? '' })));
  console.log(`Đã ghi ${REPORT_FILE}`);
}

// ---------------------------------------------------------------- main
async function main() {
  const data = JSON.parse(await readFile(DATA_FILE, 'utf8'));
  const records = buildRecords(data).filter((r) => !ONLY || ONLY.has(r.key));
  if (!records.length) throw new Error('Không có bản ghi nào để chạy (kiểm tra --only).');
  if (data.defaults?.bbox) records.bbox = data.defaults.bbox; // [minLat,maxLat,minLng,maxLng] as object

  const errors = validate(records);
  if (errors.length) {
    console.error('Dữ liệu không hợp lệ:\n' + errors.join('\n'));
    process.exit(1);
  }
  console.log(`Dữ liệu hợp lệ: ${records.length} địa điểm.`);
  for (const r of records.filter((x) => x.reviewNeeded)) console.warn(`[${r.key}] CẦN KIỂM TRA: ${r.reviewNeeded}`);

  if (DRY_RUN) {
    console.log('Đang tải địa điểm từ OpenStreetMap để đối chiếu...');
    const osm = await fetchOsmPlaces(records);
    await writeFile(OSM_CACHE_FILE, JSON.stringify({ fetchedAt: new Date().toISOString(), places: osm }, null, 1), 'utf8');
    const rows = records.map((r) => {
      const { nameMatch, nearest } = compareWithOsm(r, osm);
      const ok = nameMatch && nameMatch.distanceMeters <= MATCH_MAX_METERS;
      return {
        key: r.key,
        name: r.name,
        result: ok
          ? 'Sẽ liên kết OSM'
          : nameMatch
            ? `Trùng tên địa điểm OSM cách ${nameMatch.distanceMeters} m (chi nhánh khác?) -> tạo PARTNER`
            : 'Không có trên OSM -> tạo PARTNER',
        osm: nameMatch ? `${nameMatch.ref} "${nameMatch.name}"` : nearest ? `(gần nhất: "${nearest.name}" ${nearest.distanceMeters} m)` : '',
        distance: nameMatch?.distanceMeters,
        gmaps: gmapsLink(r),
        osmMap: osmLink(r),
      };
    });
    await writeReport(rows, 'Dry run – đối chiếu Google Maps với OpenStreetMap');
    console.log('\n(Kết quả cuối cùng do backend quyết định khi chạy thật, dựa trên ứng viên OSM trong bán kính 500 m.)');
    return;
  }

  const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) throw new Error('Thiếu biến môi trường ADMIN_EMAIL / ADMIN_PASSWORD.');
  const admin = await login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!/ADMIN/i.test(String(admin.role))) throw new Error(`Tài khoản ${ADMIN_EMAIL} không phải admin (role=${admin.role}).`);
  globalThis.ADMIN_TOKEN = admin.token;
  console.log(`Admin đăng nhập OK. API: ${API_BASE}\n`);

  const stateAll = existsSync(STATE_FILE) ? JSON.parse(await readFile(STATE_FILE, 'utf8')) : {};
  const saveState = () => writeFile(STATE_FILE, JSON.stringify(stateAll, null, 2), 'utf8');
  if (IMAGES_MODE) {
    await runImages(records, admin.token, stateAll, saveState);
    return;
  }
  const rows = [];

  for (const r of records) {
    const state = (stateAll[r.key] ||= {});
    let result;
    try {
      const partner = await ensureAccount(r);
      state.userId = partner.id;
      state.email = r.email;
      await ensureApplication(r, partner.token, admin.token, state);
      const before = await ensureBusiness(r, partner.token, state);
      if (before === 'APPROVED') log(r.key, 'địa điểm đã được duyệt trước đó, bỏ qua');
      else await ensurePlaceApproval(r, admin.token, state);
      result = state.decision || 'APPROVED';
      state.error = null;
    } catch (err) {
      result = 'LỖI';
      state.error = err.message;
      console.error(`[${r.key}] LỖI: ${err.message}`);
    }
    await saveState();
    rows.push({
      key: r.key,
      name: r.name,
      result: state.error ? `LỖI: ${state.error}` : result,
      osm: state.osmRef ? `${state.osmRef} "${state.osmName}"` : '',
      distance: state.distanceMeters,
      gmaps: gmapsLink(r),
      osmMap: osmLink(r),
    });
  }
  await writeReport(rows, 'Kết quả seed partner');
  console.log(`\nMật khẩu các tài khoản partner: giá trị SEED_PASSWORD (mặc định Partner@2026).`);
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
