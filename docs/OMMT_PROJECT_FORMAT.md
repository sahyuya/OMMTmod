# OMMT local project format

OMMT 2.1.0 stores unfinished client-side editing projects in `OMMT/saves` with the `.ommt` extension. This is a local editor format, not the OYMI upload format and not part of the OyasaiMusic wire contract.

## Version 1 / 2 container

- 4-byte big-endian magic: `OMMT`
- unsigned 16-bit format version: `1` (legacy) or `2` (current)
- unsigned 16-bit flags: `1` for GZIP
- 32-bit uncompressed size
- 32-bit compressed size
- 32-bit CRC32 of the uncompressed body
- bounded GZIP body

The body contains the song title, viewport, named parts, notes, selected-note identities, tempo/signature/grid data, tempo control points, custom Minecraft sound IDs and patterns, and note-level pseudo-release profiles. Stable runtime IDs are regenerated while loading and the selected-note indices are remapped to those new IDs.

Version 2 adds absolute pitch cents after each note's display pitch. It uses stable OMMT
instrument IDs 0..19, including the four trumpet variants (16,17,18,19 = normal,
exposed, oxidized, weathered). Version 1 keeps its historical 0..15 instrument range.
The sourceChannel field is source identity: MIDI uses 0..15, NBS uses the original
instrument byte 0..255, and -1 means unknown. Neither field changes the record layout.

## Safety limits

- 64 MiB maximum compressed body and 128 MiB maximum expanded body
- 1,000,000 notes and 4,096 parts
- bounded UTF-8 strings, timing marks, tempo points and signature marks
- enum, number, range, custom-sound syntax, size, checksum and trailing-data validation before the editor state is replaced
- atomic local save through a temporary sibling file when the filesystem supports it

The format contains data only. It cannot load classes, run scripts or select arbitrary server-side files. Loading a project never sends it to a server; upload still requires the explicit **Upload Draft** action and normal OyasaiMusic validation.
