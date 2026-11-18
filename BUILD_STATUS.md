# Minecraft Parcel 0.2.0 — build status

## Implemented in this milestone

- Client-only `P` key mapping, registered through NeoForge's key mapping event.
- Key handling on the client tick; opens the Parcel screen only while a world is active and no other screen is open.
- A `Parcel` button added to the vanilla player inventory screen.
- A first in-game `ParcelScreen` with a close button and clear status text.
- English localization for the key mapping, inventory button, and screen.
- Mod version updated to 0.2.0.

## Not implemented yet

- Reading actual vanilla Friends List records / UUIDs.
- Friend selection and recipient verification.
- Inventory slot selection and ItemStack serialization.
- Authenticated send/inbox endpoints wired into the client.
- Crash-safe parcel reservation, acknowledgement, and claim completion.

The screen is intentionally an interface milestone, not yet an operational mail system. Do not use it to transfer valuable items.

## Build and run

From the project root, enter the Nix development shell and run:

```bash
nix develop
cd mod
./gradlew clean build
./gradlew runClient
```

The default key is P and can be changed under Options → Controls → Miscellaneous. Open the player inventory to see the additional Parcel button.
