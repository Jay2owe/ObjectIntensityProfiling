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

### Documented
- For single-slice images the `MarginalZ` and `PCThird` curves hold one filled middle bin.
  They are still written so 2D and 3D tables share their rows; outputs are unchanged.
- Behaviour for one-voxel, one-line and single-object inputs (blank values, class 1).

### Deferred
- True 3D Zernike moments: this release measures each object's maximum-intensity projection.
  Planned for a later version.
- ROI inputs in folder batches: pairing one ROI set with each sample needs its own file-name
  pattern design, so batches keep label-image input in this version.
- Profile-shape classes from several curve types at once (for example radial and shell
  concatenated). This release clusters one curve type per run.
