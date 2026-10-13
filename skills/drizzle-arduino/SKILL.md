---
name: drizzle-arduino
description: Build Arduino sketches and install their dependencies through a running Arduino IDE 1.8.19 with
  the Drizzle plugin. Use when asked to compile/verify an Arduino sketch, or to fetch the boards, libraries and
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
- `drizzle --remote status --sketch <path>` shows the IDE's state: selected board, open sketches, unsaved
  changes, the running job.
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

Drizzle doesn't flash boards. When the sketch should go onto the board, ask the user to click Upload in the IDE.
If flashing fails to connect (e.g. "Failed to connect", "could not open port", a timeout) and several serial ports
exist, the selected port is probably the wrong one: ask the user to pick the right one under Tools → Port and retry.

`@ArduinoTool` markers (IDE plugins) aren't installed remotely: ask the user to use Tools → Drizzle → Install tools
marked with @ArduinoTool and to restart the IDE afterwards.

## Rules

- Edit sketch files on disk; before each command Drizzle shows the files you changed, added or deleted in the IDE.
- Edit the markers or `drizzle.json` yourself rather than asking the IDE to insert markers.
- Don't edit Arduino's `preferences.txt` or files inside the Arduino installation.
