# Minecraft Parcel

Minecraft Parcel is a NeoForge 26.2 mod with a self-hosted delivery backend. It allows players to send item parcels to other whitelisted Minecraft players and collect them later from a different single-player world.

The project consists of two components: a Minecraft client mod and a Node.js backend. The backend can be hosted locally or deployed independently using Docker.

## Features

### Minecraft Mod

The Parcel interface can be opened using **P**. The key can be changed under Options → Controls → Miscellaneous.

A **Parcel** button is also available beneath the player inventory.

The mod communicates with the Parcel API to send and retrieve deliveries. The vanilla Minecraft Friends List is intended to serve as the recipient directory.

### Backend API

The backend provides the following functionality:

- Sending parcels to whitelisted Minecraft players.
- Retrieving pending deliveries from the inbox.
- Claiming deliveries before collecting their contents.
- Completing deliveries after successful collection.
- Preventing duplicate deliveries through request IDs.
- Temporary claim leases to prevent simultaneous collection.
- Persistent JSON-based storage.
- Minecraft profile name and UUID resolution through Mojang's profile services.
- Configurable player whitelist with automatic reloading.

### Self-hosting

The backend can be deployed using Docker Compose and published through GitHub Container Registry (GHCR).

Each server operator maintains their own whitelist and parcel database. The whitelist can be edited directly on the host system. Changes are detected automatically without restarting the container.

## Requirements

The Minecraft mod requires Minecraft Java 26.2, NeoForge 26.2 and JDK 25.

Building the backend requires Node.js 20 or newer.

Running the containerized backend requires Docker and the Docker Compose plugin.

## Project Structure

```text
MinecraftParcel/
├── backend/
│   ├── data/
│   │   └── parcels.json
│   ├── Dockerfile
│   ├── package.json
│   └── server.mjs
├── config/
│   ├── whitelist.example.json
│   └── whitelist.json
├── mod/
│   ├── src/
│   ├── build.gradle
│   └── gradlew
├── docker-compose.yml
├── build-linux.sh
├── build-windows.bat
└── README.md
```

The actual `config/whitelist.json` and the contents of `backend/data/` are local operator data and should not be committed to the repository.

## Building the Minecraft Mod

Open the `mod/` directory in IntelliJ IDEA with JDK 25 configured.

Run the Gradle build:

```bash
cd mod
./gradlew clean build
```

On Windows, use:

```bat
cd mod
gradlew.bat clean build
```

The resulting mod JAR is located in:

```text
mod/build/libs/
```

Install the generated JAR in the Minecraft `mods` directory of a compatible NeoForge 26.2 installation.

## Running the Backend Locally

Navigate to the backend directory and install the dependencies:

```bash
cd backend
npm install
```

Start the API:

```bash
HOST=127.0.0.1 PORT=8787 npm start
```

The API listens on port `8787` by default.

The health endpoint is available without authentication:

```http
GET /health
```

Example response:

```json
{
  "ok": true
}
```

## Self-hosting with Docker

The recommended deployment method is Docker Compose.

Create the configuration and data directories in the project directory:

```bash
mkdir -p config backend/data
```

Create your own whitelist from the example:

```bash
cp config/whitelist.example.json config/whitelist.json
```

Edit `config/whitelist.json` and add the players who should be allowed to use the Parcel server.

Example:

```json
{
  "players": [
    {
      "name": "PlayerOne",
      "uuid": "",
      "enabled": true
    },
    {
      "name": "PlayerTwo",
      "uuid": "",
      "enabled": true
    }
  ]
}
```

The UUID field can be left empty. The backend will attempt to resolve the Minecraft profile automatically.

Set `enabled` to `false` to disable a player without removing their entry.

Create an empty parcel database if one does not exist:

```bash
echo '{"deliveries":[]}' > backend/data/parcels.json
```

Start the backend:

```bash
docker compose up -d
```

Check the container logs:

```bash
docker compose logs -f
```

Check the API:

```bash
curl http://127.0.0.1:8787/health
```

### Docker Compose Configuration

The following configuration uses the published GHCR image:

```yaml
services:
  parcel-backend:
    image: ghcr.io/skaydar92/minecraft-parcel-backend:0.3.1
    container_name: minecraft-parcel-backend
    restart: unless-stopped

    ports:
      - "8787:8787"

    environment:
      HOST: 0.0.0.0
      PORT: 8787
      NODE_ENV: production
      PARCEL_DB: /app/data/parcels.json
      PARCEL_WHITELIST: /app/config/whitelist.json

    volumes:
      - ./backend/data:/app/data
      - ./config:/app/config:ro
```

The `backend/data` directory stores parcel deliveries persistently. The `config` directory contains the operator-managed whitelist.

The whitelist is mounted read-only inside the container. Operators edit the file directly on their host system.

The backend automatically detects changes to the whitelist and reloads it while running. A container restart is not required.

### Managing the Whitelist

To add a player, edit `config/whitelist.json` and add a new entry.

To remove access, set the player's `enabled` property to `false` or remove the entry entirely.

The backend reloads the whitelist automatically after the file has been changed.

Each operator controls their own player whitelist independently. No central whitelist service is required.

## API Reference

All API endpoints except `/health` require a whitelisted player identity.

The backend currently identifies the requesting player through the `X-Parcel-Player` HTTP header.

### Health Check

```http
GET /health
```

Returns the API health status.

### Retrieve Inbox

```http
GET /v1/inbox
X-Parcel-Player: PlayerName
```

Returns pending deliveries for the authenticated player.

### Create Delivery

```http
POST /v1/deliveries
X-Parcel-Player: PlayerName
Content-Type: application/json
```

Example request:

```json
{
  "recipientName": "PlayerTwo",
  "requestId": "unique-request-id-12345",
  "items": [
    {
      "id": "minecraft:diamond",
      "count": 16
    }
  ]
}
```

The recipient must exist in the server whitelist. Players cannot send parcels to themselves.

The `requestId` is used to prevent duplicate deliveries from repeated requests by the same sender.

### Claim Delivery

```http
POST /v1/deliveries/{id}/claim
X-Parcel-Player: PlayerName
```

Claims a delivery and returns its contents together with a claim token.

A claim is valid for five minutes. If it expires without completion, the delivery becomes available again.

### Complete Delivery

```http
POST /v1/deliveries/{id}/complete
X-Parcel-Player: PlayerName
Content-Type: application/json
```

Example request:

```json
{
  "claimToken": "claim-token-returned-by-claim"
}
```

Marks the delivery as completed after successful collection.

## Data Storage

The backend uses JSON files for persistent storage.

`backend/data/parcels.json` contains the delivery database, including sender and recipient information, delivery status and item payloads.

`config/whitelist.json` contains the players allowed to use the backend.

Both locations should be backed up regularly.

## Security Considerations

The current `X-Parcel-Player` header is not cryptographic authentication. A client can potentially impersonate another player by changing the header.

The current authentication mechanism is therefore intended for development and controlled environments. It must be replaced with a secure identity verification mechanism before exposing the service to untrusted users.

For public deployment, use HTTPS, implement proper authentication, apply rate limits and restrict access to the backend where appropriate.

Never expose the backend directly to the public internet without addressing these security concerns.

## Versioning

The Minecraft mod and backend are versioned independently.

Published backend Docker images are available through GitHub Container Registry.

Example:

```bash
docker pull ghcr.io/skaydar92/minecraft-parcel-backend:0.3.1
```

Use the corresponding version tag in your Docker Compose configuration.

## Development Status

Minecraft Parcel is actively developed.

The backend currently implements the delivery lifecycle, player whitelist, persistent storage and Docker-based self-hosting.

For the current implementation status of the Minecraft mod, see [BUILD_STATUS.md](BUILD_STATUS.md).