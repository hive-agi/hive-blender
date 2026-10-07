# hive-blender

A host-neutral IAddon for the Blender MCP add-on's **base** command catalog and loopback socket.
The built-in `export_scene` supports GLB and FBX only; STL requires a separately authorized
`execute_code` operation and an installed Blender STL exporter. No native 3MF export is claimed.

## Running

Install and enable the reference Blender MCP `addon.py` in a **GUI** Blender instance, on the
same host as hive-blender. Set `BLENDERMCP_NO_UPDATE_CHECK=1` **in Blender's environment** before
launching to disable the add-on's network update check. The direct socket path never imports
its Python MCP server; hive-blender makes no telemetry, update, Premium or cloud provider calls.
Do not enable optional provider integrations in the baseline. Blender is not installed in the
current test environment; tests use an in-process fake add-on server, **not verified against a
real Blender**. Restrict access to the loopback listener: the reference add-on has no socket
authentication and other local processes can send it commands directly.

The host must install a `hive-blender.port/CodeGate` before the addon mounts; `execute_code`
requires `:confirm true`, gate approval and at most 200000 UTF-8 bytes. The gate is a policy
hook, not a Python sandbox. On socket timeout the typed `:blender/unknown-outcome` means a
mutation might already have run; never replay it automatically. Every call uses a fresh socket,
closed after reply or error, one outstanding command per socket. Configure socket time/size
bounds via `socket-link`; the destination is hardwired to IPv4 loopback, default port 9876.

The tool offers `catalog`, `doctor` and `call`; optional provider handlers are not catalogued.
Scene and object references are values containing file identity and names, not `bpy` pointers.
See [native seams](docs/native-seams.md) for deployment limits and unanswered questions.

Run `clojure -M:test` and `dev/verify_portability.sh` for the fake-server conformance and
JVM/cljw/cljrs/cljs portable codec checks. The GPL bambu-printer-mcp bridge is used for
interoperability facts only; redistributing or requiring it from an MIT addon needs a separate
licensing decision.

License: MIT.
