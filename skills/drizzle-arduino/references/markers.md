# Drizzle markers and drizzle.json

A sketch declares everything it needs from the IDE: the platform (core), the board and its menu options, the
libraries, and extra build properties. `apply-markers` installs and selects what is declared, and every compile
(Verify or remote) applies the build properties.

Full reference: the "Supported Markers" and "Alternative Usage" sections of https://github.com/zhgzhg/Drizzle

## Where Drizzle reads the declarations

1. **`drizzle.json` in the sketch folder** (next to the main `.ino`), if it exists. It is then the only source:
   markers in the `.ino` are ignored.
2. Otherwise, **markers in comments of the main `.ino` file** (the one named after the sketch folder).

So check for `drizzle.json` first, and change the declarations where Drizzle actually reads them. Both forms carry
the same information with the same rules, described below for markers.

## Markers in the main `.ino`

- Only comments in the main `.ino` count; usually they sit in a block comment at its top. Being plain comments,
  they keep the sketch valid for IDEs without Drizzle.
- One marker per line. Nothing containing `@` may precede it on that line (a leading `*` or `//` is fine).
- The rest of the line is the marker's value, so close a block comment (`*/`) on a line of its own.
- Fields are separated by `::`, so names can't contain `:`. Names, menus and options are case-sensitive.
- Mind the colons when taking a board from an FQBN (fully qualified board name), as shown by `list-fqbns` and in the
  `fqbn` field of `status`. The FQBN `arduino:avr:nano` names the package (`arduino`), the platform's architecture
  (`avr`) and the board ID (`nano`), separated by single colons. Markers use `::` between the same parts and call
  the package the provider: `@Board arduino::avr::nano`. A marker written with single colons is silently ignored.
- Versions are npm semver ranges: `1.0.0`, `^2.6.3`, `~1.6`, `>1.0.3`, `(>= 1.0.8 && < 1.0.16)`, `*` (latest).

```cpp
/*
 * @BoardManager Arduino AVR Boards::^1.8.6
 * @Board arduino::avr::nano
 * @BoardSettings avr::nano::Processor->ATmega328P (Old Bootloader)
 * @DependsOn ArduinoJson::^7.0.0
 * @Preferences arduino::avr::nano::compiler.cpp.extra_flags=-DDEBUG_LEVEL=2
 */
```

## The markers

In the markers, the *platform* is the architecture from the FQBN (`avr`), except in `@BoardManager`, which takes the
platform's name as the Boards Manager lists it (`Arduino AVR Boards`); `@Board` accepts either. For some platforms
both are the same, e.g. `esp32`.

**`@BoardManager platform name::version[::index URL]`** installs a platform, e.g.
`@BoardManager Arduino AVR Boards::^1.8.6` or
`@BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json`.
The URL is the Boards Manager index of a third-party platform; Drizzle adds it to the IDE's additional URLs.
Only the first valid `@BoardManager` counts. Arduino IDE 1.x keeps one version per platform, so applying it
uninstalls the platform's other installed versions, which other sketches may still use.

**`@Board [provider::]platform::board id or name`** selects the board, e.g. by IDs `@Board arduino::avr::nano`, or
by names `@Board arduino::Arduino AVR Boards::Arduino Nano`. The board ID is matched before the name; without the
provider, Drizzle finds it. Only the first valid `@Board` counts. `drizzle --remote list-fqbns` lists the known
boards as FQBNs; FQBN `arduino:avr:nano` becomes `@Board arduino::avr::nano`.

**`@BoardSettings platform::board id or name::Menu->Option[||Menu->Option...]`** sets options of the board's
Tools menus, e.g. `@BoardSettings esp32::*::Flash Frequency->40MHz||PSRAM->Disabled`. `Menu->Option` picks an
option in a menu, and `||` lists several of them, which are all set, one after another: it's a separator, not an
"or". `*` matches any platform or board. Several markers may match the board: the first one setting a menu wins,
and later ones still set the other menus, so write the specific markers first and the general ones (`*::*::...`)
last. Use the menu and option labels exactly as the IDE shows them (`Processor->ATmega328P (Old Bootloader)`);
`drizzle --remote autogen-markers --mode board` returns them for the current selection.

**`@DependsOn library::version or URI`** installs a library; use one marker per library (the first one for a name
counts). Instead of a version, a URI can point to:

- an `https://` `.zip` file;
- an `https://` `.git` repository, optionally with `#tag`, `#branch` or `#commit`;
- a `file:///` `.zip` file or library folder.

With a URI, the name only has to be unique, not the library's real name. Libraries that a library needs are not
installed automatically: `apply-markers` reports them in `result.problems`; add them as `@DependsOn` markers.

**`@Preferences [provider::]platform::board id or name::property=value[||property=value...]`** adds build
properties at compile time, e.g. `@Preferences *::esp32::*::compiler.cpp.extra_flags=-std=gnu++17`. As in
`@BoardSettings`, `||` lists several properties, which are all added: a separator, not an "or". `*` matches
any provider, platform or board. When several matching markers define the same property, the first one wins and the
later ones still add their other properties, so again: specific markers first, general ones last.

**`@ArduinoTool name::version condition::zip URL`** installs an IDE plugin. Remote commands don't handle it; see
SKILL.md.

## drizzle.json

The same declarations as JSON; every key is optional. This is the example above:

```json
{
  "board_manager": { "platform": "Arduino AVR Boards", "version": "^1.8.6" },
  "board": { "providerPackage": "arduino", "platform": "avr", "name": "nano" },
  "board_settings": [
    { "board": { "platform": "avr", "name": "nano" },
      "clickable_options": [["Processor", "ATmega328P (Old Bootloader)"]] }
  ],
  "libraries": { "ArduinoJson": { "version": "^7.0.0" } },
  "preferences": [
    { "board": { "providerPackage": "arduino", "platform": "avr", "name": "nano" },
      "preferences": { "compiler.cpp.extra_flags": "-DDEBUG_LEVEL=2" } }
  ]
}
```

A third-party platform's index URL goes into `"url"` next to `"platform"` and `"version"` in `board_manager`.

- `"*"` is the wildcard in the `board` objects, as in markers.
- The order of `board_settings` and `preferences` entries matters the same way as the order of markers: the first
  entry setting a menu or property wins.
- A library's `version` may be a URI, as with `@DependsOn`.
- `@ArduinoTool` entries go into `"arduino_ide_tools": { "<name>": { "version": "...", "url": "..." } }`.
- To convert: `drizzle --rev-parse drizzle.json` prints the equivalent markers. `drizzle --parse <file.ino>` prints
  the JSON of a file's markers; you may drop its `arduinoCliFmt` fields and `null`s. To turn the markers from
  `autogen-markers` into JSON, put them in a comment of a scratch `.ino` file and parse that.

## Checking your changes

- `drizzle --parse <sketch folder>` shows, as JSON and without the IDE, what Drizzle will use: the folder's
  `drizzle.json` if it has one, otherwise the main `.ino`'s markers. A marker missing from the output is malformed;
  a malformed `drizzle.json` makes the command fail (exit code -5), whereas the IDE would silently fall back to the
  markers.
- `drizzle --parse <file.ino>` always parses that file's markers, even when a `drizzle.json` sits next to it.
- Then `drizzle --remote apply-markers` reports in `result.problems` what couldn't be installed or selected.
