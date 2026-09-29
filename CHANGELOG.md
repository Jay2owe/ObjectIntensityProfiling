# Changelog

All notable changes to Object Intensity Profiling are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 0.3.0 - 2026-09-29

First release on the Fiji update site (`Object-Intensity-Profiling`). New measurements are
off by default; with default settings every existing output is byte-identical to 0.2.0.

### Added
- Golden output digests (`src/test/resources/oip/golden/digests.txt`) guarding every CSV and
  class-map TIFF the plugin writes, for a seeded matrix of single-image and batch inputs.
- Optional Zernike moments (`zernike`, `zernike_degree=`; off by default) written to
  `Profiles/Object_Zernike.csv` and an interactive *Object Zernike* table. Existing outputs are
  unchanged when the option is off.
- Optional profile-shape classes (`profile_classes`, `profile_k=`, `profile_class_type=`; off
  by default): objects are grouped by the shape of one normalised curve per partner channel,
  written to `Profiles/Profile_Classes.csv`, `Aggregate/Profile_Class_Curves.csv` and a
  class-mean figure per partner, with an interactive *Object Profile Classes* table. A folder
  batch fits the classes once across every sample.
- ROI inputs for single images: `objects_roi=[...]` defines objects from an ImageJ ROI set
  instead of a label image, and `region_roi=[...]` profiles only objects whose centroid lies
  inside a region ROI set. Both are also in the *Open images* dialog and the Java API
  (`OipRoiInputs`, `OipParameters.Builder.regionRois`).

### Changed
- Macro options are parsed by one shared table in single-image and batch mode. An option given
  twice, or with its opposite (`glcm no_glcm`), is now rejected instead of the last one winning.
  Batch mode accepts `save_dir` and `auto_save`; options that belong to the other mode are
  rejected with a message naming the right one.
- Expected errors are shown as a one-line message (and thrown in headless runs) instead of an
  exception window; two open images with the same title are rejected.
- Texture classes and profile-shape classes share one deterministic k-means implementation;
  texture-class results are unchanged.

### Performance
- Each run now fetches every image slice once and shares it with all workers, instead of asking
  ImageJ for the slice again for every object (ImageJ rescans a whole slice on each request when
  the displayed slice is blank). Unused per-voxel maths is skipped when a profile family is off.
  Outputs are bit-identical. Synthetic benchmark (`oip.bench.OipBenchmark`, 1,024 objects, two
  channels; serial / eight workers):

  | Image and measurement | Before | After |
  |---|---|---|
  | 512 x 512 x 24, profiles | 12.5 s / 2.7 s | 1.1 s / 0.33 s |
  | 512 x 512 x 24, GLCM | 8.4 s / 1.7 s | 2.0 s / 0.49 s |
  | 512 x 512 x 24, texture classes | 6.7 s / 1.5 s | 2.0 s / 0.56 s |
  | 2048 x 2048 x 10, profiles | 157 s / 24.7 s | 1.9 s / 0.83 s |

- A virtual stack is read into memory once per run (and logged with its size) rather than read
  from disk again for every object.

### Fixed
- RGB label or raw images are rejected with a message instead of being measured as packed
  colour values.
- A raw channel whose calibration differs from the label image is rejected (single images
  and batch preflight); an uncalibrated 3D label image logs a warning.
- 32-bit label values above 16,777,216, which cannot be stored exactly and could merge
  objects, are rejected.
- Batch preflight lists every all-background sample in one message instead of stopping at
  the first.
- The "no finite pixels" GLCM range error names the channel and, in a batch, the sample.
- Progress no longer jumps backwards while fixed GLCM ranges are scanned for several
  channels, and batch range-scan progress no longer counts channels with manual ranges.
- A macro that passes a bad option (or is cancelled) now stops with a one-line message.
  Previously, in headless Fiji, ImageJ printed a Java stack trace and the macro carried on with
  its next line.

### Documented
- For single-slice images the `MarginalZ` and `PCThird` curves hold one filled middle bin.
  They are still written so 2D and 3D tables share their rows; outputs are unchanged.
- Behaviour for one-voxel, one-line and single-object inputs (blank values, class 1).

### Testing
- `src/test/fiji/run-smoke.sh` runs the packaged plugin headless inside a disposable Fiji;
  `SmokeParityTest` checks that Fiji and the unit-test build give identical 2D tables.

### Re-scoped to a later version
- 3D GLCM: co-occurrence is measured per 2D slice and combined per object. A true 3D
  co-occurrence matrix needs 13 directions and new reliability rules, so it waits for a later
  version.
- 3D texture classes: the Gabor/wavelet features are computed on each object's 2D
  maximum-intensity projection; 3D filter banks would change every class assignment.
- True 3D Zernike moments: this release measures each object's maximum-intensity projection.
  3D moments need a different basis (Zernike polynomials on the sphere) and output layout.
- Multi-source-channel profiling: each run profiles the partners of one label source (the first
  build plan left this out); several sources can be profiled by running once per label image.
- ROI inputs in folder batches: pairing one ROI set with each sample needs its own file-name
  pattern design, so batches keep label-image input in this version.
- Profile-shape classes from several curve types at once (for example radial and shell
  concatenated): this release clusters one curve type per run, which keeps each class easy to
  read.

## 0.2.0 - never published

Built in the repository history (commit `b7aa10f`) but never tagged or released on an update
site or GitHub Releases; `CITATION.cff` briefly named it with a 2026-08-20 date.

### Added
- Radial, marginal X/Y/Z, principal-axis, angular and concentric-shell intensity profiles.
- Object-mask sampling by default, with a padded bounding-box option.
- Per-object Pearson correlation, overlap coefficient, and Manders M1/M2.
- Optional 2D GLCM measurements (contrast, energy, correlation, entropy, homogeneity) and
  eight-feature Gabor/wavelet texture classes with deterministic k-means.
- Fixed GLCM quantisation per channel across a folder batch, with optional manual limits.
- Per-object CSV files, object-weighted aggregate curves with standard error, aggregate
  figures and class-coloured maps.
- Interactive, ImageJ macro, folder-batch and headless Java entry points; shared batch
  discovery from `oc3d-core` 0.1.0, shaded into the plugin jar.
