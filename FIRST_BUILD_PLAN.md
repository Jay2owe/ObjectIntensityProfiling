# 03 — Object Intensity Profiling

**Name settled 2026-07-30.** The plugin still contains the texture engines; the name does not
enumerate them, the way "3D Objects Counter+" does not enumerate its filters. Texture is a feature
family inside the plugin, not a co-headline.

## Naming and identity

| Item | Value |
|---|---|
| Display name | Object Intensity Profiling |
| Menu entry | `Plugins > Object Intensity Profiling` |
| Macro command | `run("Object Intensity Profiling", "...")` |
| GitHub repo | `github.com/Jay2owe/ObjectIntensityProfiling` |
| Update site | `https://sites.imagej.net/Object-Intensity-Profiling/` |
| Maven groupId | `io.github.jay2owe` |
| Maven artifactId | `Object_Intensity_Profiling` |
| Java package | `oip` (matches FLASH's existing `OipConfig` naming) |
| Entry class | `Object_Intensity_Profiling.java` |
| Built jar | `Object_Intensity_Profiling-<version>.jar` |
| Licence | BSD 3-Clause |

Citation line:

> Malcolm, J. (2026). Object Intensity Profiling (v1.0.0) [Software].
> GitHub. https://github.com/Jay2owe/ObjectIntensityProfiling

Methods-section form:

> Per-object intensity profiles were computed using the Object Intensity Profiling plugin (v1.0.0).

**Name collision check — CLEARED 2026-07-30**

| Namespace | Result |
|---|---|
| `imagej/list-of-update-sites` (commit `58b1ff6`, 329 sites) | free |
| `https://sites.imagej.net/Object-Intensity-Profiling/` | free — HTTP 404 |
| `https://imagej.net/plugins/object-intensity-profiling` | free — HTTP 404 |
| GitHub | free |

Nearest existing sites, neither a competitor: *Intensity Profile tools* (`IntensityProfileTools`)
is an interactive line-profile macro toolset; *RadialIntensityProfile* (`PTschaikner`) is a simple
single-reference nuclear radial macro.

## Settled decisions

- **Positioning: lead on cross-channel.** The headline is what CellProfiler cannot express —
  principal-axis profiles, angular ring-completeness curves, concentric-shell colocalization, and
  mask-restricted per-object Pearson/Manders. Radial profiling and GLCM are supporting features
  that bring CellProfiler-class per-object measurement into Fiji; they are **not** the pitch.
- **Zernike moments: v0.2.0, not v0.1.0.** They close the most visible gap against CellProfiler's
  radial module and are established and citable, but they are a whole feature family and v0.1.0
  already ships two halves.
- **Radial and texture stay in v0.1.0.** They are what most users will look for first, even though
  they are not the differentiator.
**Absorbs the intensity half of the former "Object Texture" plan.** GLCM and texture classes need
raw intensity images, exactly like the profiling engines, so they share this plugin's input
contract rather than justifying one of their own. The mask-based half of that plan (fractal
dimension and lacunarity) went to plugin 05, because it measures shape, not signal.

## Goal

For every segmented object, describe the raw signal *inside* it — both **where** it sits (radial,
principal-axis, angular and shell profiles) and **what it looks like** (co-occurrence texture and
unsupervised texture classes).

Every other plugin in this family reduces an object to a point, a volume or an outline. This one
keeps the object's interior and asks what is going on in there.

## Case strength: 3 of 6 — REVISED 2026-07-30 after checking CellProfiler

An earlier draft called this "the most differentiated plugin in the family" and said "nothing
comparable exists". **That was half wrong and is corrected here.**

### Verified: what is genuinely unoccupied

**In Fiji/ImageJ, both halves are unoccupied.** Checked against `imagej/list-of-update-sites`
(329 sites):

- **No texture update site exists at all** — no GLCM, Haralick, Gabor or wavelet entry.
- Only two profiling-adjacent sites, neither a competitor: *Intensity Profile tools*
  (`IntensityProfileTools`, Laurent Thomas) is an interactive line-profile macro toolset;
  *RadialIntensityProfile* (`PTschaikner`) is, by its own description, "a simple macro to analyse
  the radial distribution of a fluorescent signal of interest in relation to a cell's nucleus" —
  single-reference, not per-object across a population.
- Existing ImageJ GLCM plugins (e.g. GLCM Texture Analyzer) are **moving-window or whole-image**,
  not restricted to object masks.

### Verified: what is already occupied, on another platform

**CellProfiler already does the two headline claims**, and this must be stated plainly rather than
discovered in review:

- `MeasureObjectIntensityDistribution` produces per-object radial measurements with **normalised
  per-object rings** — FracAtD, MeanFrac, RadialCV across 8 angular slices, and Zernike moments.
- `MeasureTexture` produces **per-object** Haralick features (13 measurements, 4 directions in 2D,
  13 in 3D, settable grey-level binning).

So "per-object radial profiles" and "per-object texture" are **not new to the field**. They are new
to Fiji.

### What remains genuinely novel, even against CellProfiler

Checked against the module documentation — CellProfiler has none of these:

1. **Principal-axis profiles.** Profiling along the object's *own* PCA axes, not image axes or
   radius. CellProfiler is radius-only.
2. **Angular profiles / ring completeness.** CellProfiler's RadialCV is a scalar summary over 8
   slices, not an angular curve — it cannot tell a complete ring from a broken one.
3. **Marginal X/Y/Z profiles** along image axes.
4. **Concentric-shell colocalization.** Inner/mid/outer shell overlap *between channels*, per
   object.
5. **Mask-restricted per-object Pearson and Manders.** Pixel-based colocalization computed inside
   each object's own mask — the bridge between object-based and intensity-based colocalization.

Items 4 and 5 are the real contribution, and they share a property CellProfiler's modules lack:
they are **cross-channel**. CellProfiler measures one image's distribution within one object set.
This plugin profiles a *partner* channel's signal inside a *source* object's mask, which is a
different construct and the one that connects this plugin to the rest of the family.

### The honest positioning

Not "nothing like this exists". Rather: **the cross-channel and axis-aware profiles are new; the
rest brings CellProfiler-class per-object measurement into Fiji, batched, segmentation-decoupled,
and without building a pipeline.** That is still a strong release — the Fiji audience is large and
largely does not use CellProfiler — but it is a utility-plus-methods release, not a pure methods
paper, and the paper should foreground items 1–5 rather than radial profiling in general.

Concrete questions it answers that CellProfiler cannot: is the ring complete or broken? Is signal
polarised along the object's own long axis? Does partner signal sit in the inner, middle or outer
shell? What is the Pearson correlation between two channels *inside this one object*?

### Worth stealing from CellProfiler

**Zernike moments** — rotationally invariant descriptors of intensity distribution, magnitude and
phase. CellProfiler has them, this plugin does not, and they are a well-established, citable
feature family that would strengthen the per-object descriptor set. Consider for v0.2.0.

## Inputs needed

| Input | Required | Notes |
|---|---|---|
| 1 label image (source objects) | yes | defines the objects and the mask |
| 1–4 raw intensity images (partners) | **yes** | the whole plugin measures raw signal |
| Voxel calibration | important | anisotropic Z distorts radial profiles and 3D GLCM offsets |
| ROI `.zip` region set | optional | restricts which objects are profiled |

The raw-image requirement is a real departure from CPC's contract: raw and label images must match
in dimensions, and the dialog must validate that pairing up front rather than failing mid-run. CPC
already has the pairing machinery from intensity-weighted centroids (`raw1=`…`raw5=` macro options,
raw folder and raw regex in `CPCBatchParameters`) — that is the starting point.

## Outputs

**Profiling half**

- Per-object curve rows on a **fixed-length normalised axis**, so curves average directly across
  objects regardless of object size. This normalisation is the design decision that makes the whole
  thing work.
- Profile families: radial (distance from centre), marginal X/Y/Z (image axes), principal-axis (the
  object's own axes via PCA), angular (ring completeness), concentric-shell colocalisation
  (inner/mid/outer), within-box correlation (Pearson and overlap against the source channel).
- Scalar summaries per object: radial polarity, shell ratios, within-box Pearson, axis skew.
- Population aggregate curves (mean ± spread) per channel pair, and aggregate figures.

**Texture half**

- GLCM features per object, computed on the object's quantised intensity patch: contrast, ASM
  (energy), correlation, entropy, homogeneity.
- Texture-class assignment from an 8-value feature vector (four Gabor orientation responses, four
  wavelet scale energies) via k-means, with distance to the assigned centroid.
- Class-coloured maps.

Auto-save tree: `Profiles/`, `Texture/`, `Aggregate/`, `Figures/`, `Maps/`.

## How the texture computation actually works

Worth stating precisely, because it determines the input contract:

- **GLCM** (`ObjectTextureGLCM`): takes each object's raw intensity patch, quantises it to 32 grey
  levels, builds 2D co-occurrence matrices at four directions — (1,0), (0,1), (1,1), (−1,1) —
  counting how often each pair of grey levels occurs in neighbouring pixels, then averages valid
  per-slice results per object. All five features are moments of that matrix. **Requires raw
  intensity; the label image only supplies the mask.**
- **Texture classes** (`ObjectTextureFeatures`): convolves each object's intensity patch with Gabor
  filters at four orientations and computes wavelet energies at four scales, giving a fixed 8-value
  vector, then assigns each object to the nearest k-means centroid. **Also requires raw intensity.**
- Both use `ObjectPatchBuilder` to cut a per-object patch carrying *both* an intensity array and a
  mask, which is exactly the same primitive the profiling engines need.

Quantisation is currently min–max **within each patch**, which means values are not comparable
between objects or between images. Fixing this with a fixed quantisation range across the batch is
mandatory for the batch mode to mean anything.

## Functionality to match (the CPC standard)

All ten points. Specific to this plugin:

- "Restrict to object voxels only" toggle — profile within the mask, or within the padded bounding
  box including surrounding context. Both are legitimate and answer different questions.
- Bounding-box padding percentage.
- Curve length (bin count) settable, with the normalisation guarantee documented.
- **Fixed grey-level quantisation range across a batch**, not per-object auto-ranging.
- Batch aggregation weighted by object count, not by image.
- GLCM and texture classes are expensive — progress reporting and cancellation are mandatory, and
  they should be off by default (FLASH marks them "(slow)" for good reason).
- A minimum object size below which texture features are **suppressed**, not reported as noise.

## Reference style from CPC

Same chassis, with the largest input adaptation of any plugin in the family: paired label + raw
selection with dimension validation, both interactively and in batch. Per-object tables are wide
(one column per bin per profile per partner), so the CSV writer needs more care than CPC's —
FLASH's `ObjectProfileCsvWriter` already solves this.

## Source material in FLASH

Internal FLASH classes, not standalone plugins. The largest ready-made body of code in the family —
roughly 2,000 lines that lift almost intact.

| FLASH source | Lines | What it gives |
|---|---|---|
| `objects/ObjectIntensityProfiler.java` | 430 | the profiling engine; documented as pure and reentrant, no static state, no window-manager calls |
| `objects/ObjectProfileResult.java` | 79 | per-object result model |
| `objects/ObjectProfileCsvWriter.java` | 172 | wide-format curve CSV writing |
| `objects/ObjectProfileCsvReader.java` | 133 | reading profiles back for aggregation |
| `objects/ProfileAggregator.java` | 135 | population averaging |
| `objects/ObjectProfileFigureWriter.java` | 125 | aggregate figures |
| `objects/OipConfig.java` | 82 | which profiles, bin counts, padding |
| `objects/SymmetricEigen3.java` | 93 | 3×3 eigen decomposition for principal axes |
| `morphometry/ObjectTextureGLCM.java` / `…GLCM3D.java` | 214 / 246 | per-object GLCM, 2D and native 3D |
| `morphometry/ObjectTextureFeatures.java` / `…3D.java` | 441 / 445 | Gabor + wavelet feature vectors and class assignment |
| `morphometry/ObjectPatchBuilder.java`, `ObjectPatch.java`, `ObjectPatch3D.java` | 431 | per-object intensity+mask patch extraction — shared by both halves |
| `intensity/spatial/GlcmTextureAnalysis.java` | 281 | whole-ROI GLCM reference implementation (32 levels, 4 offsets) |
| `docs/how_tos/per-object-texture-glcm.md`, `per-object-texture-classes.md` | — | **user-facing documentation, already written** |
| `src/test/java/flash/pipeline/morphometry/ObjectTexture*Test.java` | — | existing tests to port |

The profiler was written to be callable from parallel workers, so it was already designed for
extraction. `ObjectPatchBuilder` is the shared primitive that makes merging the two halves natural
rather than forced.

## New beyond FLASH

1. **Standalone existence.** In FLASH these are only reachable inside 3D Object Analysis with a
   full config. Most of the value here is simply making them runnable.
2. **Batch mode** with correct cross-image aggregation and fixed quantisation.
3. **Per-object Pearson and Manders restricted to the object mask** — pixel-based colocalisation
   computed per object rather than per image. FLASH has `withinBoxPearson`; extending to
   mask-restricted Manders gives a genuinely new measure bridging object-based and intensity-based
   colocalisation. **The strongest single addition available**, and it feeds plugin 06 directly.
4. **Profile-shape clustering** — group objects by curve shape rather than by scalar features.
5. **Minimum-size suppression** for texture features.

v0.1.0: items 1, 2, 5, and item 3 if at all possible. v0.2.0: item 4.

## Dependencies

`ij` only. The eigen solver, Gabor and wavelet code are all self-contained in FLASH.

## Pros

- Most differentiated plugin in the family — a real methods contribution, not a better version of
  something that exists.
- Most code already written, and written to a portable standard, with tests and user documentation.
- Mask-restricted per-object intensity colocalisation is a measure people would cite specifically.
- Merging texture in costs almost nothing: same input contract, same patch primitive, same dialog
  section, and it saves a weak standalone release.
- Produces striking figures, which matters for adoption.

## Cons

- Requires raw images, breaking the family's otherwise uniform label-only contract and roughly
  doubling the input-validation surface.
- Hardest to explain in one sentence, which is exactly what CPC got right and FLASH got wrong. The
  naming pass matters more here than anywhere else — and adding texture makes the name harder, not
  easier.
- Centroid-relative and axis-relative profiles are unstable for irregular, branched or ring-shaped
  objects — the same critique that applies to CPC centroids, but worse, because the entire output
  is geometry-dependent.
- GLCM is sensitive to quantisation, offset, direction and object size; small objects give unstable
  features and users will not notice.
- Computationally the heaviest plugin in the family.
- Wide output tables are unwieldy in Excel; the aggregate view must be the default presentation.

## First build (v0.1.0) scope

In: single source label image plus 1–4 raw partners with dimension validation; radial / marginal /
principal-axis / angular / shell profiles; within-box correlation; restrict-to-mask toggle; box
padding; per-object GLCM with fixed batch quantisation; texture classes with settable k; minimum
object size guard; per-object curve and texture CSVs; population aggregate curves and figures;
class-coloured maps; batch with correct aggregation; auto-save; macro options; Java API; JUnit
tests on synthetic objects with analytically known profiles and textures (uniform sphere, shell,
half-filled object, known checkerboard and gradient textures).

Out: 3D GLCM and 3D texture classes, profile-shape clustering, multi-source-channel profiling,
mask-restricted Manders if it threatens the timeline.

## Open questions

- Should multiple source channels be profiled in one run, or is one source at a time the honest
  scope? FLASH does one source per call.
- Is "restrict to object voxels" or "padded bounding box" the better default? They give different
  answers and users will not read the tooltip.
- Does mask-restricted per-object Pearson/Manders belong here or in plugin 06? Here it is a
  differentiator; there it is one row in a menu. Probably here, exposed there.
- Does the texture half dilute the plugin's story badly enough to reconsider? The input contract
  argues for merging; the one-sentence pitch argues against.
