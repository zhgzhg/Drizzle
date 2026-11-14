---
name: drizzle-arduino
description: Build Arduino sketches, upload them to boards, read what the boards print in the Serial Monitor, and
  install their dependencies through a running Arduino IDE 1.8.19 with the Drizzle plugin. Use when asked to
  compile/verify or upload an Arduino sketch, to check a board's serial output, or to fetch the boards, libraries and
  board settings that its Drizzle markers (@BoardManager, @Board, @BoardSettings, @DependsOn, @Preferences) or its
  drizzle.json declare.
---

# Drizzle remote control

Drizzle runs inside Arduino IDE 1.8.19 and accepts commands from the local machine. Talk to it only through its
launcher, which finds the right IDE instance and handles the connection details:

- Windows: `%USERPROFILE%\.drizzle\bin\drizzle.cmd`
- Linux / macOS: `~/.drizzle/bin/drizzle`

The launcher is (re)written every time the IDE starts with Drizzle. If it's missing, or
`drizzle --remote list` reports no instances (exit code 3), ask the user to start Arduino IDE 1.8.19 with
Drizzle 0.17.0 or newer installed.

`upload`, `list-ports`, `list-programmers` and the Serial Monitor commands need Drizzle 0.18.0 or newer;
`drizzle --remote list` shows each IDE's version as `drizzle`. Older versions reject them with `Unknown remote command`
or `Unknown argument` (exit code 2): ask the user to update Drizzle instead of changing the call.

## Markers and drizzle.json

A sketch declares what it needs (platform, board, board options, libraries, build properties) either in a
`drizzle.json` file in the sketch folder, or, when there is none, as Drizzle markers in a comment at the top of its
main `.ino` file. A `drizzle.json` takes over completely: markers in the `.ino` are then ignored. `apply-markers`
installs and selects what is declared, and every compile applies the build properties.

Before writing or changing these declarations, read [references/markers.md](references/markers.md). To check your
changes, run `drizzle --parse <sketch folder>`: it shows what Drizzle will use, from whichever source applies.

## Workflow

Always pass the absolute path of the sketch folder (or its main `.ino` file).

1. `drizzle --remote list` shows the running IDEs and the sketches they have open.
2. If the sketch isn't open in any IDE (exit code 4): `drizzle --remote open --sketch <path>`.
3. `drizzle --remote apply-markers --sketch <path>` installs the platform and libraries the sketch declares
   and selects the board and its options. Run it before the first compile, after changing the declarations, and before
   compiling a different sketch: the IDE has one board selection, shared by all its windows.
   `result.problems` lists what couldn't be installed or selected.
4. `drizzle --remote compile --sketch <path>` compiles exactly like the IDE's Verify button, including
   `@Preferences`. On failure, `result.diagnostics` lists `file`, `line`, `column`, `severity` and `message`.
   Fix the code and compile again. Add `--verbose` only when the full compiler command lines are needed.

Other commands:

- `drizzle --remote autogen-markers --sketch <path> [--mode board|all]` returns suggested markers based on the
  IDE's current board selection (`board`), plus the libraries found by a compilation (`all`, the default).
  Nothing is written: copy what you need into the sketch's markers or `drizzle.json` yourself.
- `drizzle --remote list-fqbns [--sketch <path>]` lists the boards the IDE knows, as `package:platform:board`.
  Markers separate these parts with `::` instead of `:`; see the reference.
- `drizzle --remote status --sketch <path>` shows the IDE's state: selected board, port and programmer, the Serial
  Monitor, open sketches, unsaved changes, the running job.
- `drizzle --parse <sketch folder>` prints what the sketch declares (its `drizzle.json`, or else its markers) as JSON,
  without needing the IDE.

## Results

Every command prints one JSON object on stdout; `ok` tells whether it succeeded and `error.code` /
`error.message` explain why not. The exit code says what to do next:

| Exit | Meaning | What to do |
|------|---------|------------|
| 0 | Success | Continue |
| 1 | The command ran but failed (e.g. compilation errors) | Read `result` and `log`, fix, retry |
| 1 | `error.code` is `cancelled`: the user (or you) stopped the command | Don't retry without asking the user |
| 1 | `error.code` is `port_unavailable`: no port is selected, or it isn't connected | See [Uploading](#uploading) |
| 1 | `error.code` is `password_required`: a network board needs its password | See [Uploading](#uploading) |
| 1 | `error.code` is `monitor_closed`: sending needs an open Serial Monitor | See [Serial Monitor](#serial-monitor) |
| 2 | Bad arguments, or the sketch is open in more than one IDE | Fix the call; pass `--instance <id>` from `list` |
| 3 | No running IDE with Drizzle | Ask the user to start Arduino IDE |
| 4 | Sketch not open in any IDE | Run `open` first |
| 5 | Busy with another command or with an action started in the IDE | Wait a little, then retry |
| 6 | The sketch has unsaved changes in the IDE | Ask the user to save or revert them; never work around it |
| 7 | Timed out while the command keeps running in the IDE | `drizzle --remote job <job-id>` (add `--wait` to keep waiting) |
| 8 | The IDE doesn't respond (often a dialog waiting for an answer), or another communication problem | Retry once, then tell the user |

## Long-running commands

Installing a platform can take several minutes. Pass `--timeout <seconds>` shorter than your own command timeout.
On exit code 7 the job keeps running in the IDE; its id is in the JSON output (`job`) and in `status`.
`drizzle --remote cancel <job-id>` stops a job that hangs or that you no longer need.

## Uploading

An upload replaces the program on the board, and with it what the board does to whatever it's connected to. Upload
only when the user asks for it, never just because a compile succeeded.

1. `drizzle --remote list-ports` lists the ports as Tools → Port does and marks the selected one. A port can show
   the board the IDE recognises on it (`board`, `fqbn`) and its USB ids (`vid`, `pid`). Ports without USB ids, such
   as Bluetooth COM ports, are rarely the board. If more than one port could be the board, ask the user which one.
2. `drizzle --remote upload --sketch <path> [--port <address>]` compiles and uploads like the IDE's Upload button.
   `--port` selects that port in the IDE first, as Tools → Port does, and it stays selected; without it the IDE's
   selected port is used. On failure, `result.error.stage` says whether compiling or uploading failed, and
   `result.errorOutput` holds what the uploader reported. `--verbose` makes the uploader report in detail.

When an upload fails to reach the board (e.g. "not in sync", "Failed to connect", a timeout), don't change the code:

- with several ports, the selected one is probably wrong: ask the user;
- some boards need their BOOT or RESET button pressed while uploading: ask the user;
- `port_unavailable`: the port isn't connected (anymore), or none is selected. The message lists the available
  ports; pass `--port` if exactly one of them is clearly the board, otherwise ask the user.

`--programmer <id>` uploads through a programmer, like Upload Using Programmer, with an id from
`drizzle --remote list-programmers` (the programmers of the selected board). Use it only when the user asks: on AVR
boards it erases the bootloader, so uploading through the port stops working until a bootloader is burned again. In
that list, `uploadUsesProgrammer: true` means the board has no bootloader: even a plain upload goes through the
selected programmer.

Network (OTA) ports with `passwordRequired: true` fail with `password_required`: the IDE asks for the password in a
dialog. Ask the user to upload once from the IDE; it then remembers the password until it exits.

## Serial Monitor

While the IDE's Serial Monitor is open (or its Serial Plotter, if the user opened that instead), Drizzle records what
it receives, with each line's arrival time. The window stays visible to the user.

- `drizzle --remote serial-open [--port <address>] [--baud <rate>]` opens the Serial Monitor like Tools → Serial
  Monitor. Pass the rate of the sketch's `Serial.begin(...)`; `--port` and `--baud` stay selected in the IDE, as if
  the user chose them. Opening the monitor, or changing its rate, restarts boards like the Uno, Nano or Mega; ESP32
  boards and boards with native USB (Leonardo, RP2040...) keep running.
- `drizzle --remote serial-log` returns the lines as `{"t": "14:03:12.345", "text": "..."}`, along with what happened
  (`event`: opened, paused for the upload, closed in the IDE...) and what was sent (`sent`):
  - `--from`: `start` (the oldest line kept), `upload` (the last upload on: the new program's output), `now`, a line
    number (the `next` of an earlier call), a time from earlier lines (`14:03:12.345`), or a time before now (`-30s`).
    Without it: the newest lines.
  - `--to`: a time, `-5s`, or a duration after `--from` (`+10s`). Without it: up to the newest line.
  - `--max-lines <n>` (default 200): when `more` is true, continue with `--from <next>`.
  - `--wait-for-regex <regex>` waits until a line matches; `match` holds that line.
  - `--timeout <seconds>` (default 30, at most 600) limits any waiting; on its own, the call collects what arrives
    for that long, which is also the way to wait for a while. Keep it below your own command timeout.
  - A `--to` in the future makes the call wait until then. `stopped` tells why a wait ended: `match`, `to`, `timeout`
    or `closed` (the monitor got closed).
- `drizzle --remote serial-send --text <text> [--line-ending none|nl|cr|crlf]` sends text to the board like the
  monitor's input field, by default with the line ending chosen there. It acts on the board: send only what the user
  asked for, or agreed to let you send.
- `drizzle --remote serial-close` closes the monitor (or plotter) and frees the port.

After changing a sketch:

1. Open the monitor before uploading.
2. `drizzle --remote upload --sketch <path>` pauses the monitor and reopens it afterwards.
3. `drizzle --remote serial-log --from upload --wait-for-regex "READY|ERROR" --timeout 20`

The board starts the new program as soon as the upload ends, about a second before the monitor reopens. Boards that
restart when the monitor opens (Uno, Nano, Mega) start over then, so nothing is lost. On the others, what the program
prints in its first second is lost: wait for something it prints later or repeatedly, or, if you need the startup
lines, ask the user to press the board's reset button once the monitor is open again.

Notes:

- Garbled text usually means a wrong baud rate: check `Serial.begin(...)` and pass it to `serial-open --baud`.
- A line without a line break (e.g. a prompt) shows up once nothing gets added to it for a quarter of a second.
- Times are when the IDE received the text (about 30 ms resolution); if timing matters, have the sketch print
  `millis()`.
- The user may close the monitor or click Clear, which also clears the record (an `event` says so). If the user
  closed the monitor, ask before reopening it.
- Before another program (a script, arduino-cli...) uses the port, close the monitor. Uploads through Drizzle or the
  IDE don't need that.
- `serial-open` fails while the Serial Plotter is open on another port or rate: ask the user to close it.
- `serial-log` doesn't run as a job: it works while another command runs. Monitors of network boards aren't recorded.

## Left to the user

- `@ArduinoTool` markers (IDE plugins): ask the user to use Tools → Drizzle → Install tools marked with
  @ArduinoTool, and to restart the IDE afterwards (Drizzle 0.18.0 and newer: Tools → Drizzle → Restart Arduino IDE).
- Burning a bootloader (Tools → Burn Bootloader).

## Rules

- Edit sketch files on disk; before each command Drizzle shows the files you changed, added or deleted in the IDE.
- Edit the markers or `drizzle.json` yourself rather than asking the IDE to insert markers.
- Don't edit Arduino's `preferences.txt` or files inside the Arduino installation.
