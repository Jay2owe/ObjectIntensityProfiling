# Changelog

All notable changes to Object Intensity Profiling are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Added
- Golden output digests (`src/test/resources/oip/golden/digests.txt`) guarding every CSV and
  class-map TIFF the plugin writes, for a seeded matrix of single-image and batch inputs.
- Optional Zernike moments (`zernike`, `zernike_degree=`; off by default) written to
  `Profiles/Object_Zernike.csv` and an interactive *Object Zernike* table. Existing outputs are
  unchanged when the option is off.

### Changed
- Macro options are parsed by one shared table in single-image and batch mode. An option given
  twice, or with its opposite (`glcm no_glcm`), is now rejected instead of the last one winning.
  Batch mode accepts `save_dir` and `auto_save`; options that belong to the other mode are
  rejected with a message naming the right one.
- Expected errors are shown as a one-line message (and thrown in headless runs) instead of an
  exception window; two open images with the same title are rejected.

### Deferred
- True 3D Zernike moments: this release measures each object's maximum-intensity projection.
  Planned for a later version.
