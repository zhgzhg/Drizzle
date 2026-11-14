---
name: drizzle-arduino
description: Build Arduino sketches, upload them to boards, read what the boards print in the Serial Monitor, and
  install their dependencies through a running Arduino IDE 1.8.19 with the Drizzle plugin. Use when asked to
  compile/verify or upload an Arduino sketch, to check a board's serial output, or to fetch the boards, libraries and
  board settings that its Drizzle markers (@BoardManager, @Board, @BoardSettings, @DependsOn, @Preferences) or its
  drizzle.json declare.
---

# Drizzle

Drizzle runs inside Arduino IDE 1.8.19 and accepts commands from the local machine through its launcher:

- Windows: `%USERPROFILE%\.drizzle\bin\drizzle.cmd`
- Linux / macOS: `~/.drizzle/bin/drizzle`

Before anything else, run `drizzle --guide` and follow what it prints: the instructions of the Drizzle version the
user has installed, which the IDE keeps up to date. `drizzle --guide markers` prints the reference for Drizzle's
markers and `drizzle.json`; the guide says when to read it.

- The IDE (re)writes the launcher every time it starts with Drizzle. If it's missing, ask the user to start Arduino
  IDE 1.8.19 with Drizzle installed.
- If the launcher answers `--guide` with "Incorrect arguments" or "Unrecognized argument", the installed Drizzle is
  older than 0.18.0: ask the user to update it (Arduino IDE offers the update when it starts).
- Upload to a board, or send it anything, only when the user asks for it.
