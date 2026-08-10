# AGENTS.md

Guidance for AI coding agents and human contributors working on this repository.

This file is a living document. Update it as the project changes and as
conventions become more concrete.

## Project

Burp Suite extension built on the Montoya API. It adds a chat panel to Burp
that talks to an LLM endpoint, and integrates with the Repeater tool: requests
can be sent from Repeater into the chat, and model output can be sent back to
Repeater with the correct target applied. See README.md for features and usage.

## Requirements

- Java: the project targets Java 21 and uses modern Java features (switch
  expressions, pattern matching for `instanceof` and switch, `StringBuilder.repeat`,
  `List.getFirst()`). Use a JDK 21 or newer to build and run it.
- Burp Suite with the Montoya API runtime.
- Dependencies are declared in pom.xml: montoya-api plus commonmark jars.

## Source layout

All source files live in `src/main/java/`:

- `MyExtension.java` — main entry point; settings tab, chat tab, chat and
  streaming logic, send-to-Repeater
- `MarkdownRenderer.java` — renders Markdown into a Swing styled document
- `RepeaterContextMenuProvider.java` — Repeater right-click menu and target
  capture
- `LogManager.java` — leveled logging to file and the Burp tabs

Classes currently live in the default (unnamed) package. Moving them into a
named package is a suggested future cleanup.

## Building and verifying

- There is no automated test suite. Verify changes by compiling, then loading
  the jar in Burp manually.
- After any edit, make sure the project still compiles. Set up a compile check
  that works in your own environment: for example, compile the `.java` files
  under `src/main/java` with javac using the dependency jars on the classpath
  (versions in pom.xml), or rely on the Maven build. The exact command is up to
  you; the requirement is that the code compiles with no errors after your
  edits.
- Package the jar with Maven: `mvn package`. Output: `target/burp-poc.jar`
  (shaded, includes dependencies). Any Maven 3.x works, system-installed or
  IDE-bundled. This repo does not include a Maven wrapper.
- An IntelliJ artifact build is also supported and produces an equivalent jar.

## Code conventions

- Add Javadoc to every class, method, and field (public and private), with
  `@param`, `@return`, and `@throws` where applicable. Editors and IDEs rely on
  this when people are contributing.
- Write comments, Javadoc, documentation, and README content in plain, clear
  language: short sentences, one idea per sentence, active voice, no
  embellishment. (STE-100 as inspiration, not a strict spec.)
- Inline comments explain the "why", not the "how".
- Keep documentation current: when a feature or behavior changes, update the
  related Javadoc, comments, README, and AGENTS.md in the same change.

## Logging

- Use `LogManager` levels correctly:
  - `log()` — general activity
  - `debug()` — more detail
  - `complete()` — everything, including every SSE delta
  - `error()` — errors
- Logs are written to a timestamped file and to the Burp Output/Errors tabs.
- Never log API keys. Use the existing masking (see `maskApiKey` in
  MyExtension) or equivalent.

## Key flows to be careful with

- Repeater target resolution (`MyExtension.sendSelectionToRepeater`): use the
  service captured from Repeater first; otherwise parse the `Host:` header from
  the selection; otherwise send without an explicit target. Log which source
  was used. Normalize chat text (trim per-line trailing whitespace, convert
  line endings to CRLF before parsing) and strip stray CR/LF from the parsed
  method, path, and header values before sending, so Burp does not flag the
  request as an HTTP/2 "kettled" request.
- Streaming: accumulate deltas, re-render the full buffer on each delta, run
  network work off the UI thread, and update Swing components via
  `SwingUtilities.invokeLater`.
- Settings auto-save on every change; the `restoringSettings` flag guards
  against saving while values are being loaded.
- Markdown rendering (`MarkdownRenderer`): lenient table repair, code-block
  and link custom attributes, heading anchors.

## Repository etiquette

- Never commit or push unless explicitly asked. Leave commits to the maintainer
  for review.
- Never commit secrets. The API key is stored in Burp preferences and must be
  masked in any logs.
- Generated and local files (`*.log`, `out/`, `target/`) are gitignored.