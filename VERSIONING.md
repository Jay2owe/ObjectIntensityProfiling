# Versioning

This project uses semantic versioning:

- patch releases fix behaviour without intentionally changing outputs;
- minor releases add backward-compatible measurements or options;
- major releases may change defaults, column meaning, file layout, or APIs.

During development, Maven builds may use `-SNAPSHOT`. A release removes that
suffix, updates `CHANGELOG.md` and `CITATION.cff`, and tags the matching
version (`v0.3.0`).

Default outputs are guarded by golden digests
(`src/test/resources/oip/golden/digests.txt`). A new option must be off by
default and leave existing outputs byte-identical; any change to an existing
output, column definition or threshold meaning must be called out explicitly
in the changelog.
