
import http from 'node:http';
import {
    randomUUID,
    timingSafeEqual
} from 'node:crypto';
import {
    readFile,
    writeFile,
    rename
} from 'node:fs/promises';

const PORT = Number(process.env.PORT || 8787);
const DB_FILE = process.env.PARCEL_DB || './parcels.json';
const CLAIM_LEASE_MS = 5 * 60 * 1000;
const MAX_BODY_SIZE = 1_000_000;

const TOKEN_MAP = new Map(
    (process.env.PARCEL_TOKENS || '')
        .split(',')
        .filter(Boolean)
        .map(pair => {
            const i = pair.indexOf(':');
            return [pair.slice(0, i), pair.slice(i + 1)];
        })
);

let state = { deliveries: [] };

try {
    state = JSON.parse(await readFile(DB_FILE, 'utf8'));
    if (!Array.isArray(state.deliveries)) {
        state = { deliveries: [] };
    }
} catch {
    state = { deliveries: [] };
}

let writeChain = Promise.resolve();

const persist = () => {
    writeChain = writeChain.then(async () => {
        const tmp = DB_FILE + '.tmp';

        await writeFile(
            tmp,
            JSON.stringify(state, null, 2),
            { mode: 0o600 }
        );

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

const auth = req => {
    const token = (req.headers.authorization || '')
        .replace(/^Bearer\s+/i, '');

    if (!token) {
        return null;
    }

    for (const [uuid, expected] of TOKEN_MAP) {
        const a = Buffer.from(token);
        const b = Buffer.from(expected);

        if (a.length === b.length && timingSafeEqual(a, b)) {
            return uuid;
        }
    }

    return null;
};

const validUuid = value =>
    typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

const isClaimExpired = delivery =>
    delivery.status === 'CLAIMED'
    && (!delivery.claimExpiresAt
        || Date.parse(delivery.claimExpiresAt) <= Date.now());

const server = http.createServer(async (req, res) => {
    try {
        const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

        if (url.pathname === '/health' && req.method === 'GET') {
            return json(res, 200, { ok: true });
        }

        const sender = auth(req);

        if (!sender) {
            return json(res, 401, { error: 'unauthorized' });
        }

        if (url.pathname === '/v1/inbox' && req.method === 'GET') {
            const deliveries = state.deliveries
                .filter(delivery =>
                    delivery.recipientUuid === sender
                    && (
                        delivery.status === 'PENDING'
                        || isClaimExpired(delivery)
                    )
                )
                .map(delivery => ({
                    id: delivery.id,
                    senderUuid: delivery.senderUuid,
                    createdAt: delivery.createdAt,
                    status: isClaimExpired(delivery)
                        ? 'PENDING'
                        : delivery.status,
                    items: delivery.items
                }));

            return json(res, 200, { deliveries });
        }

        if (url.pathname === '/v1/deliveries'
            && req.method === 'POST') {
            const data = await body(req);

            if (!validUuid(data.recipientUuid)
                || !Array.isArray(data.items)
                || data.items.length < 1
                || data.items.length > 36) {
                return json(res, 400, {
                    error: 'recipientUuid and 1..36 item stacks are required'
                });
            }

            if (data.recipientUuid === sender) {
                return json(res, 400, {
                    error: 'cannot send parcels to yourself'
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
                    return json(res, 400, {
                        error: 'invalid item stack'
                    });
                }
            }

            const existing = state.deliveries.find(delivery =>
                delivery.senderUuid === sender
                && delivery.requestId === data.requestId
            );

            if (existing) {
                return json(res, 200, {
                    deliveryId: existing.id,
                    status: existing.status,
                    duplicateRequest: true
                });
            }

            const delivery = {
                id: randomUUID(),
                requestId: data.requestId,
                senderUuid: sender,
                recipientUuid: data.recipientUuid,
                createdAt: new Date().toISOString(),
                status: 'PENDING',
                items: data.items
            };

            state.deliveries.push(delivery);
            await persist();

            return json(res, 201, {
                deliveryId: delivery.id,
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
                return json(res, 404, {
                    error: 'delivery not found'
                });
            }

            if (delivery.status === 'CLAIMED'
                && !isClaimExpired(delivery)) {
                return json(res, 409, {
                    error: 'delivery is already being claimed'
                });
            }

            if (delivery.status !== 'PENDING'
                && !isClaimExpired(delivery)) {
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
                return json(res, 404, {
                    error: 'delivery not found'
                });
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

server.listen(PORT, () => {
    console.log(`Minecraft Parcel API listening on :${PORT}`);
});
