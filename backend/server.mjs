
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile, writeFile, rename } from 'node:fs/promises';
import { watch } from 'node:fs';
import path from 'node:path';

const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 8787);
const DB_FILE = process.env.PARCEL_DB || './parcels.json';
const WHITELIST_FILE = process.env.PARCEL_WHITELIST || './whitelist.json';

const CLAIM_LEASE_MS = 5 * 60 * 1000;
const MAX_BODY_SIZE = 1_000_000;
const PROFILE_CACHE_MS = 15 * 60 * 1000;
const NEGATIVE_CACHE_MS = 30 * 1000;
const WHITELIST_RELOAD_DELAY_MS = 300;

let state = { deliveries: [] };
let whitelist = new Set();

try {
    state = JSON.parse(await readFile(DB_FILE, 'utf8'));

    if (!Array.isArray(state.deliveries)) {
        state = { deliveries: [] };
    }
} catch {
    state = { deliveries: [] };
}

let writeChain = Promise.resolve();
const profileCache = new Map();

function normalizeUuid(value) {
    if (typeof value !== 'string') return '';

    const compact = value.replaceAll('-', '').toLowerCase();

    if (!/^[0-9a-f]{32}$/.test(compact)) return '';

    return [
        compact.slice(0, 8),
        compact.slice(8, 12),
        compact.slice(12, 16),
        compact.slice(16, 20),
        compact.slice(20)
    ].join('-');
}

function isValidPlayerName(value) {
    return typeof value === 'string'
        && /^[a-zA-Z0-9_]{3,16}$/.test(value);
}

async function fetchJson(url) {
    const response = await fetch(url, {
        signal: AbortSignal.timeout(5000),
        headers: {
            accept: 'application/json',
            'user-agent': 'MinecraftParcel/0.3.0'
        }
    });

    if (!response.ok) {
        if (response.status === 404) return null;
        throw new Error(`Minecraft profile API returned ${response.status}`);
    }

    return response.json();
}

async function resolvePlayerName(name) {
    if (!isValidPlayerName(name)) {
        throw new Error('Ungültiger Minecraft-Spielername.');
    }

    if (name.toLowerCase() === 'dev') {
        return {
            uuid: '380df991-f603-344c-a090-369bad2a924a',
            name: 'Dev'
        };
    }

    const cacheKey = `name:${name.toLowerCase()}`;
    const cached = profileCache.get(cacheKey);

    if (cached && cached.expiresAt > Date.now()) {
        if (!cached.profile) {
            throw new Error('Minecraft-Spieler wurde nicht gefunden.');
        }

        return cached.profile;
    }

    const result = await fetchJson(
        `https://api.minecraftservices.com/minecraft/profile/lookup/name/${encodeURIComponent(name)}`
    );

    if (!result?.id || !result?.name) {
        profileCache.set(cacheKey, {
            profile: null,
            expiresAt: Date.now() + NEGATIVE_CACHE_MS
        });

        throw new Error('Minecraft-Spieler wurde nicht gefunden.');
    }

    const profile = {
        uuid: normalizeUuid(result.id),
        name: result.name
    };

    if (!profile.uuid) {
        throw new Error('Ungültige UUID vom Minecraft-Profilservice.');
    }

    profileCache.set(cacheKey, {
        profile,
        expiresAt: Date.now() + PROFILE_CACHE_MS
    });

    profileCache.set(`uuid:${profile.uuid}`, {
        profile,
        expiresAt: Date.now() + PROFILE_CACHE_MS
    });

    return profile;
}

async function resolveUuid(uuid) {
    const normalized = normalizeUuid(uuid);

    if (!normalized) return null;

    const cacheKey = `uuid:${normalized}`;
    const cached = profileCache.get(cacheKey);

    if (cached && cached.expiresAt > Date.now()) {
        return cached.profile;
    }

    const compact = normalized.replaceAll('-', '');
    const result = await fetchJson(
        `https://sessionserver.mojang.com/session/minecraft/profile/${compact}`
    );

    if (!result?.id || !result?.name) {
        profileCache.set(cacheKey, {
            profile: null,
            expiresAt: Date.now() + NEGATIVE_CACHE_MS
        });

        return null;
    }

    const profile = {
        uuid: normalizeUuid(result.id),
        name: result.name
    };

    profileCache.set(cacheKey, {
        profile,
        expiresAt: Date.now() + PROFILE_CACHE_MS
    });

    profileCache.set(`name:${profile.name.toLowerCase()}`, {
        profile,
        expiresAt: Date.now() + PROFILE_CACHE_MS
    });

    return profile;
}

async function loadWhitelist() {
    const data = JSON.parse(await readFile(WHITELIST_FILE, 'utf8'));

    if (!data || !Array.isArray(data.players)) {
        throw new Error('whitelist.json muss ein players-Array enthalten.');
    }

    const allowed = new Set();

    for (const entry of data.players) {
        if (!entry || entry.enabled === false) continue;

        if (!isValidPlayerName(entry.name)) {
            console.warn('Ungültiger Name in whitelist.json:', entry.name);
            continue;
        }

        let uuid = normalizeUuid(entry.uuid);

        if (!uuid) {
            try {
                const profile = await resolvePlayerName(entry.name);
                uuid = profile.uuid;
            } catch (error) {
                console.warn(
                    `Whitelist-Eintrag für ${entry.name} konnte nicht aufgelöst werden:`,
                    error.message
                );
                continue;
            }
        }

        allowed.add(uuid);
        console.log(`Parcel-Whitelist: ${entry.name} (${uuid})`);
    }

    whitelist = allowed;

    console.log(`${whitelist.size} Spieler in der Parcel-Whitelist geladen.`);
}

function watchWhitelist() {
    const whitelistDirectory = path.dirname(WHITELIST_FILE);
    const whitelistFilename = path.basename(WHITELIST_FILE);

    let reloadTimer;

    const watcher = watch(whitelistDirectory, (eventType, filename) => {
        if (filename && filename.toString() !== whitelistFilename) {
            return;
        }

        clearTimeout(reloadTimer);

        reloadTimer = setTimeout(async () => {
            try {
                await loadWhitelist();
                console.log('Parcel-Whitelist wurde aktualisiert.');
            } catch (error) {
                console.error(
                    'Whitelist konnte nicht neu geladen werden. Die bisherige Whitelist bleibt aktiv:',
                    error.message
                );
            }
        }, WHITELIST_RELOAD_DELAY_MS);
    });

    watcher.on('error', error => {
        console.error('Fehler bei der Whitelist-Dateiüberwachung:', error);
    });

    console.log(`Überwache Whitelist: ${WHITELIST_FILE}`);

    return watcher;
}

const persist = () => {
    writeChain = writeChain.then(async () => {
        const tmp = DB_FILE + '.tmp';

        await writeFile(tmp, JSON.stringify(state, null, 2), {
            mode: 0o600
        });

        await rename(tmp, DB_FILE);
    });

    return writeChain;
};

const json = (res, code, data) => {
    res.writeHead(code, {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store'
    });

    res.end(JSON.stringify(data));
};

const body = async req => {
    let raw = '';

    for await (const chunk of req) {
        raw += chunk;

        if (raw.length > MAX_BODY_SIZE) {
            throw new Error('Body too large');
        }
    }

    return JSON.parse(raw || '{}');
};

async function auth(req) {
    const playerName = req.headers['x-parcel-player'];

    if (!isValidPlayerName(playerName)) return null;

    try {
        const profile = await resolvePlayerName(playerName);

        if (!whitelist.has(profile.uuid)) return null;

        return profile.uuid;
    } catch (error) {
        console.error('Parcel-Anmeldung fehlgeschlagen:', error.message);
        return null;
    }
}

const isClaimExpired = delivery =>
    delivery.status === 'CLAIMED'
    && (!delivery.claimExpiresAt
        || Date.parse(delivery.claimExpiresAt) <= Date.now());

async function getDisplayName(uuid, storedName) {
    try {
        const profile = await resolveUuid(uuid);
        return profile?.name || storedName || 'Unbekannter Spieler';
    } catch (error) {
        console.error('Spielername konnte nicht aufgelöst werden:', error.message);
        return storedName || 'Unbekannter Spieler';
    }
}

const server = http.createServer(async (req, res) => {
    try {
        const url = new URL(
            req.url,
            `http://${req.headers.host || 'localhost'}`
        );

        if (url.pathname === '/health' && req.method === 'GET') {
            return json(res, 200, { ok: true });
        }

        const sender = await auth(req);

        if (!sender) {
            return json(res, 401, { error: 'not_whitelisted' });
        }

        if (url.pathname === '/v1/inbox' && req.method === 'GET') {
            const deliveries = await Promise.all(
                state.deliveries
                    .filter(delivery =>
                        delivery.recipientUuid === sender
                        && (
                            delivery.status === 'PENDING'
                            || isClaimExpired(delivery)
                        )
                    )
                    .map(async delivery => ({
                        id: delivery.id,
                        senderName: await getDisplayName(
                            delivery.senderUuid,
                            delivery.senderName
                        ),
                        createdAt: delivery.createdAt,
                        status: isClaimExpired(delivery)
                            ? 'PENDING'
                            : delivery.status,
                        items: delivery.items
                    }))
            );

            return json(res, 200, { deliveries });
        }

        if (url.pathname === '/v1/deliveries' && req.method === 'POST') {
            const data = await body(req);

            if (!isValidPlayerName(data.recipientName)
                || !Array.isArray(data.items)
                || data.items.length < 1
                || data.items.length > 36) {
                return json(res, 400, {
                    error: 'recipientName and 1..36 item stacks are required'
                });
            }

            if (typeof data.requestId !== 'string'
                || data.requestId.length < 16
                || data.requestId.length > 100) {
                return json(res, 400, {
                    error: 'valid requestId is required'
                });
            }

            for (const item of data.items) {
                if (!item || typeof item !== 'object' || Array.isArray(item)) {
                    return json(res, 400, { error: 'invalid item stack' });
                }
            }

            const existing = state.deliveries.find(delivery =>
                delivery.senderUuid === sender
                && delivery.requestId === data.requestId
            );

            if (existing) {
                return json(res, 200, {
                    deliveryId: existing.id,
                    recipientName: await getDisplayName(
                        existing.recipientUuid,
                        existing.recipientName
                    ),
                    status: existing.status,
                    duplicateRequest: true
                });
            }

            let recipient;

            try {
                recipient = await resolvePlayerName(data.recipientName);
            } catch (error) {
                return json(res, 400, { error: error.message });
            }

            if (!whitelist.has(recipient.uuid)) {
                return json(res, 403, {
                    error: 'recipient_not_whitelisted'
                });
            }

            const senderName = await getDisplayName(sender);

            const delivery = {
                id: randomUUID(),
                requestId: data.requestId,
                senderUuid: sender,
                senderName,
                recipientUuid: recipient.uuid,
                recipientName: recipient.name,
                createdAt: new Date().toISOString(),
                status: 'PENDING',
                items: data.items
            };

            state.deliveries.push(delivery);
            await persist();

            return json(res, 201, {
                deliveryId: delivery.id,
                recipientName: recipient.name,
                status: delivery.status,
                duplicateRequest: false
            });
        }

        const claimMatch = url.pathname.match(
            /^\/v1\/deliveries\/([0-9a-f-]+)\/claim$/i
        );

        if (claimMatch && req.method === 'POST') {
            const delivery = state.deliveries.find(
                item => item.id === claimMatch[1]
            );

            if (!delivery || delivery.recipientUuid !== sender) {
                return json(res, 404, { error: 'delivery not found' });
            }

            if (delivery.status === 'CLAIMED' && !isClaimExpired(delivery)) {
                return json(res, 409, {
                    error: 'delivery is already being claimed'
                });
            }

            if (delivery.status !== 'PENDING' && !isClaimExpired(delivery)) {
                return json(res, 409, {
                    error: 'delivery is no longer available'
                });
            }

            const claimToken = randomUUID();

            delivery.status = 'CLAIMED';
            delivery.claimToken = claimToken;
            delivery.claimedBy = sender;
            delivery.claimedAt = new Date().toISOString();
            delivery.claimExpiresAt = new Date(
                Date.now() + CLAIM_LEASE_MS
            ).toISOString();

            await persist();

            return json(res, 200, {
                deliveryId: delivery.id,
                claimToken,
                senderName: await getDisplayName(
                    delivery.senderUuid,
                    delivery.senderName
                ),
                items: delivery.items
            });
        }

        const completeMatch = url.pathname.match(
            /^\/v1\/deliveries\/([0-9a-f-]+)\/complete$/i
        );

        if (completeMatch && req.method === 'POST') {
            const data = await body(req);

            const delivery = state.deliveries.find(
                item => item.id === completeMatch[1]
            );

            if (!delivery || delivery.recipientUuid !== sender) {
                return json(res, 404, { error: 'delivery not found' });
            }

            if (delivery.status !== 'CLAIMED'
                || delivery.claimedBy !== sender
                || delivery.claimToken !== data.claimToken) {
                return json(res, 409, {
                    error: 'claim token invalid or expired'
                });
            }

            delivery.status = 'DELIVERED';
            delivery.deliveredAt = new Date().toISOString();

            delete delivery.claimToken;
            delete delivery.claimExpiresAt;

            await persist();

            return json(res, 200, {
                deliveryId: delivery.id,
                status: delivery.status
            });
        }

        return json(res, 404, { error: 'not found' });
    } catch (error) {
        console.error('Parcel API error:', error);

        return json(res, 400, {
            error: error.message || 'bad request'
        });
    }
});

await loadWhitelist();
const whitelistWatcher = watchWhitelist();

server.listen(PORT, HOST, () => {
    console.log(`Minecraft Parcel API listening on http://${HOST}:${PORT}`);
});

server.on('close', () => {
    whitelistWatcher.close();
});
