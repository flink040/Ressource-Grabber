# Resource Grabber

Resource Grabber is a client-side Fabric mod for Minecraft 26.2. It saves
successfully downloaded server resource packs and provides a searchable browser
for their Custom Model Data items.

This branch targets Minecraft 26.2. The Minecraft 1.21.11 source remains on the
[`main`](https://github.com/flink040/Ressource-Grabber/tree/main) branch.

## Features

- Copies downloaded server packs into the active Minecraft instance's
  `resourcepacks` directory.
- Compares complete SHA-256 hashes so identical packs are not saved twice.
- Keeps changed packs as dated versions named `<server-name> - <date>.zip`.
- Supports modern `assets/<namespace>/items/*.json` range dispatches and legacy
  `models/item` overrides.
- Opens a searchable, paginated item browser with `/customitems`.
- Provides selectable item cards, visible Give/Place/Wear/Details actions,
  persistent favorites, category filters, status badges, and a responsive detail
  panel for item IDs, model paths, CMD values, lore, and ChestShop data.
- Places any listed model as a persistent `item_display` in singleplayer by
  right-clicking it, then right-clicking a block in the world.
- Opens a compact placement editor for precise position, rotation, scale, and
  removal controls. Placement works without cheats or `/summon` commands.
- Reopens the editor for existing item displays with `/customitems edit`:
  look at the model, right-click, and use **Remove** when needed.
- Lets golden-horse-armor model displays behave like placed items in singleplayer:
  left-clicking removes the display and returns the exact custom item.
- Shows a live placement preview and includes numeric editing, independent X/Y/Z
  rotation, adjustable step sizes, undo, reset, duplicate, face-player, and
  block-center tools.
- Exports and imports learned names, lore, and world-specific ChestShop IDs so
  players can share their collected history.
- Includes an on/off slider for learned lore in item hover tooltips.
- Shows batched, rate-limited learning feedback in the action bar and a progress
  counter in the item browser.
- Opens the chat with a matching `/give @s ...` command when an item is clicked.
  The player must review or edit the command and press Enter; Resource Grabber
  never executes it automatically. CMD values use explicit float SNBT.
- Learns server-assigned item names and lore from custom items seen in
  inventories and containers and remembers them for future sessions.
- Scans nearby ChestShop item holograms and associates them with the closest
  valid shop sign without opening the chest. Prices and player names are ignored.
- Learns the full ChestShop item ID per detected world context and can display
  known IDs in `/customitems` with a separate on/off slider.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API 0.160.0+26.2 or newer
- Java 25

## Usage

1. Place the mod JAR in the Fabric profile's `mods` directory.
2. Join a server and accept its resource pack.
3. Wait for Resource Grabber to report the detected custom items.
4. Run `/customitems` to browse and search the models.
5. Left-click an entry to prepare its `/give` command in chat.
6. In singleplayer, right-click an entry and then right-click a block to place
   it. Use the editor that opens to adjust its position, rotation, and scale.
7. To edit or remove it later, run `/customitems edit`, look at the model, and
   right-click to reopen the editor.

Single-click an item card to select it, double-click it to prepare `/give`, or
use the visible action buttons. Favorites are stored in
`config/resourcegrabber-favorites.json`.

The server still decides whether the player is allowed to use `/give`.

Saved packs use the following filename format:

```text
<server-name> - <date>.zip
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

### Sharing learned history

Use **Export** in `/customitems` to create a versioned JSON history file for the
current server. Another player can use **Import** while connected to that server
to merge the collected item names, lore, and world-specific ChestShop IDs into
their own history. Existing local names and lore are kept; new ChestShop IDs are
added without duplicates.

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

The release JAR is written to `build/libs`.

## License

Resource Grabber is licensed under the GNU General Public License v3.0 only.
Only redistribute captured server resource packs when their authors or licenses
permit it.
