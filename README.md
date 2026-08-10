# Burp Suite POC Extension

A Burp Suite extension that adds an LLM chat interface to Burp. You can send requests from Repeater into the chat, have the model modify them, and send the result back to a new Repeater tab. The target is set automatically.

Built on the [Montoya API](https://portswigger.net/burp/extender/api/). Markdown rendering uses [commonmark-java](https://github.com/commonmark/commonmark-java).

## Features

- **Chat tab** — a chat panel in Burp:
  - Streaming responses render live as Markdown (headings, bold/italic, tables, links, code blocks with a highlighted background, block quotes, lists).
  - External links open in the system browser. In-document anchor links scroll to the section.
  - Model selector (populated from the endpoint's model list), resizable input area, `Enter` to send, `Shift+Enter` for a newline, and a clear-chat button.
- **Repeater integration**:
  - Right-click any request in Repeater → **Send to POC Chat**. The request goes into the chat input box, and the request's target (host, port, scheme) is stored.
  - Select text in the chat panel → right-click → **Send to Repeater**. The selection is parsed as an HTTP request, opens in a new Repeater tab, and the target is set automatically (see [Repeater target resolution](#repeater-target-resolution)).
- **Settings tab**:
  - Endpoint type (OpenAI-compatible), endpoint URL, and API key. Settings are saved to Burp preferences automatically on change.
  - Testing tools: **Check Rate Limit**, **List Models**, **Test Chat**.
  - Configurable logging (see [Logging](#logging)).
- **Logging** — writes to the Burp Output tab and a timestamped file on disk. Levels control how much detail is written.

## Repeater target resolution

When a request is sent from the chat panel to a new Repeater tab, the target is resolved in this order:

1. **Captured service** — if the request came from **Send to POC Chat**, the Repeater request's `HttpService` (host, port, scheme) was stored and is used as-is.
2. **Parsed from the selection** — otherwise, the `Host:` header is parsed from the selected text and converted into a service. `http` is used by default; `https` is used when the port is `443`. IPv6 bracket syntax is supported.
3. **No target available** — if neither applies, the request is still sent to Repeater without an explicit target, and a log line explains why.

The resolved target is logged at `LOG` level. Example: `target set to example.com:443 (https) [captured from Repeater]`.

Before parsing, the selected text is normalized: trailing whitespace is removed from each line, and line endings are converted to CRLF (Burp's raw request parser needs CRLF delimiters; bare LF text is read as a single request line, so headers are never parsed). The method, path, and header values are then stripped of any stray CR/LF characters. This keeps model-generated requests displayable in Repeater and avoids the HTTP/2 "kettled" warning.

## Requirements

- Burp Suite (Professional or Community) with the Montoya API runtime.
- JDK 21 or newer (the project targets Java 21).
- Maven 3.x, or IntelliJ IDEA for the artifact-based build.

## Building

### Option 1 — Maven

```bash
mvn package
```

The shaded jar, with all dependencies bundled, is written to `target/burp-poc.jar`.

> The `maven-shade-plugin` bundles the runtime dependencies (commonmark, Autolink) and the Montoya API into the jar. The `montoya-api` version is pinned in `pom.xml`. Bump the version to target a newer Burp release.

### Option 2 — IntelliJ IDEA artifact

The project includes an IntelliJ artifact definition (`burp-poc:jar`). Open the project and use **Build → Build Artifacts → burp-poc:jar → Build**. The jar is written to `out/artifacts/burp_poc_jar/burp-poc.jar` with dependencies bundled. This build is independent of the Maven build. Both produce equivalent jars.

## Loading the extension

1. Open Burp Suite → **Extensions → Installed**.
2. Click **Add**.
3. Select **Java** as the extension type, then pick the built jar (`target/burp-poc.jar` or `out/artifacts/burp_poc_jar/burp-poc.jar`).
4. Two tabs appear: **Settings POC** and **Chat POC**.

## Configuration

Fill in the **Settings POC** tab first:

| Field | Purpose |
| --- | --- |
| Endpoint Type | API format (currently only OpenAI-compatible) |
| Endpoint URL | Base URL of the LLM endpoint, e.g. `https://api.example.com/v1` |
| API Key | API key used in the `Authorization: Bearer` header |
| Response Streaming | Enables server-sent-event (SSE) streaming of chat responses |
| Log Level | Output verbosity (see [Logging](#logging)) |
| Log Directory | Where the dated log file is written; leave blank for the system temp directory |

Settings are saved to Burp preferences automatically. Click **Refresh** in the Chat tab to populate the model list.

## Logging

Levels form a hierarchy. Each level includes everything below it:

| Level | Content |
| --- | --- |
| `OFF` | No file logging. Errors still go to the Burp **Errors** tab. |
| `LOG` | General activity: extension load/unload, chat request bodies, full responses (non-streaming and aggregated streaming), settings saves, model listing, rate-limit checks, and the resolved Repeater target. These lines also appear in the Burp Output tab. |
| `DEBUG` | More detail: response headers, streaming summaries, send-to-Repeater internals, rendering timings. |
| `COMPLETE` | Everything: every SSE delta, every append/re-render step, and all Markdown renderer internals. |

Log files are named `burp_poc_<yyyyMMdd_HHmmss>.log` and are written to the configured directory (default: the system temp directory). API keys are never logged. Settings logs show a masked value, e.g. `sk-r...4cd2`.

## Project structure

```
src/main/java/
├── MyExtension.java                  # Entry point: tabs, chat logic, streaming, send-to-Repeater
├── MarkdownRenderer.java             # commonmark-based markdown → styled JTextPane rendering
├── RepeaterContextMenuProvider.java  # Repeater right-click menu + target capture
└── LogManager.java                   # Leveled logging (file + Burp Output/Errors)
```

Note: classes currently live in the default (unnamed) package. Moving them into a named package is a suggested future cleanup.

## Extending the extension

- **Add a chat tool/action** — the chat request starts in `MyExtension.sendButton`'s action listener. The conversation history is a plain `List<String[]>` of `{role, content}` pairs.
- **Add output formatting** — `MarkdownRenderer` handles each commonmark node type. Extend the `AbstractVisitor` methods for more constructs.
- **Add Repeater integration** — `RepeaterContextMenuProvider` is the model for adding more context-menu actions. The target logic lives in `MyExtension.sendSelectionToRepeater` (see the captured `HttpService` flow).
- **Add logging** — use `LogManager.log`/`debug`/`complete`/`error` and pick the level per the table above.

## Security notes

- The API key is stored in Burp's persistence (plain text, like any Burp preference) and is masked in all logs.
- The API key and request content go over the network only to the endpoint URL you configured.
- Logs may contain the full text of requests and responses exchanged with the LLM. Be careful when sharing log files.
- Links opened from the chat are restricted to `http://` and `https://` schemes before the system browser opens them.