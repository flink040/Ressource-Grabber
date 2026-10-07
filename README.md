# Resource Grabber

Resource Grabber is a client-side Fabric mod for Minecraft 1.21.11. It saves
successfully downloaded server resource packs and provides a searchable browser
for their Custom Model Data items.

## Features

- Copies downloaded server packs into the active Minecraft instance's
  `resourcepacks` directory.
- Compares complete SHA-256 hashes so identical packs are not saved twice.
- Keeps changed packs as dated versions and includes the pack format/version in
  the filename.
- Supports modern `assets/<namespace>/items/*.json` range dispatches and legacy
  `models/item` overrides.
- Opens a searchable, paginated item browser with `/customitems`.
- Includes an on/off slider for learned lore in item hover tooltips.
- Shows batched, rate-limited learning feedback in the action bar and a progress
  counter in the item browser.
- Generates the matching `/give @s ...` command when an item is clicked.
- Learns server-assigned item names and lore from custom items seen in
  inventories and containers and remembers them for future sessions.
- Scans nearby ChestShop item holograms and associates them with the closest
  valid shop sign without opening the chest. Prices and player names are ignored.
- Learns the full ChestShop item ID per detected world context and can display
  known IDs in `/customitems` with a separate on/off slider.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.1 or newer
- Fabric API
- Java 21

## Usage

1. Place the mod JAR in the Fabric profile's `mods` directory.
2. Join a server and accept its resource pack.
3. Wait for Resource Grabber to report the detected custom items.
4. Run `/customitems` to browse and search the models.
5. Left-click an entry to send its `/give` command.

The server still decides whether the player is allowed to use `/give`.

Saved packs use the following filename format:

```text
serverpack-<server-address>-<date>-v<pack-version>-<sha256-prefix>.zip
```

Learned item names are stored per server in
`config/resourcegrabber-item-names.json`. Items that have not yet been seen use
their model path as a fallback name.

Learned lore is stored per server in `config/resourcegrabber-item-lore.json` and
is displayed in the `/customitems` tooltip. ChestShop scanning supports item
displays, dropped-item entities and armor-stand equipment. A shop without an
item hologram cannot expose its item data to the client until the server sends
that item through another inventory or display.

ChestShop IDs are stored in `config/resourcegrabber-chestshop-ids.json`, grouped
by server, detected scoreboard/dimension context, base item and CMD value. If a
server exposes multiple worlds with the same client-visible context, all unique
IDs are retained and displayed instead of overwriting each other.

## Configuration

The mod creates `config/resourcegrabber.json`:

```json
{
  "enabled": true,
  "showChatMessage": true,
  "learningFeedback": "subtle"
}
```

`learningFeedback` accepts `off`, `subtle`, or `detailed`. The subtle and
detailed modes show at most one batched action-bar message every five seconds;
`off` also hides the learned-items counter in `/customitems`.

## Building

Clone the repository and run:

```shell
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The remapped release JAR is written to `build/libs`.

## License

Resource Grabber is licensed under the GNU General Public License v3.0 only.
Only redistribute captured server resource packs when their authors or licenses
permit it.
