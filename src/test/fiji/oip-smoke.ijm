// Headless smoke test for Object Intensity Profiling inside a real Fiji.
// Argument: an empty work folder. Prints SMOKE lines and ends with SMOKE OK;
// any failed check stops the macro with "SMOKE FAIL: ...".
// Run it through src/test/fiji/run-smoke.sh.

work = getArgument();
if (work == "") exit("SMOKE FAIL: pass a work folder as the macro argument");
work = replace(work, "\\\\", "/");
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);

// 1. The command is registered in the menus.
List.setCommands;
command = List.get("Object Intensity Profiling");
if (command == "") exit("SMOKE FAIL: Object Intensity Profiling is not in the menus");
print("SMOKE command -> " + command);

// 2. Synthetic inputs: four discs (balls in 3D), a centre-bright and a rim-bright channel.
inputs = work + "inputs/";
File.makeDirectory(inputs);
File.makeDirectory(inputs + "batch-labels/");
File.makeDirectory(inputs + "batch-raw/");

function makeLabels(path, width, height, depth) {
    newImage("labels", "16-bit black", width, height, depth);
    radius = minOf(width, height) / 5;
    for (z = 0; z < depth; z++) {
        setSlice(z + 1);
        for (y = 0; y < height; y++) {
            for (x = 0; x < width; x++) {
                for (i = 0; i < 4; i++) {
                    cx = width * 0.28; if (i % 2 == 1) cx = width * 0.72;
                    cy = height * 0.28; if (i >= 2) cy = height * 0.72;
                    dz = 0; if (depth > 1) dz = (z - (depth - 1) / 2) * 2;
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) + dz * dz <= radius * radius)
                        setPixel(x, y, i + 1);
                }
            }
        }
    }
    saveAs("Tiff", path);
    close();
}

function makeRaw(path, width, height, depth, rim, gain) {
    newImage("raw", "32-bit black", width, height, depth);
    for (z = 0; z < depth; z++) {
        setSlice(z + 1);
        for (y = 0; y < height; y++) {
            for (x = 0; x < width; x++) {
                cx = width * 0.28; if (x > width / 2) cx = width * 0.72;
                cy = height * 0.28; if (y > height / 2) cy = height * 0.72;
                r = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (minOf(width, height) / 5);
                value = 100 * (1 - minOf(r, 1));
                if (rim) value = 100 * minOf(r, 1);
                setPixel(x, y, gain * (10 + value + ((x * 7 + y * 13 + z * 5) % 17)));
            }
        }
    }
    saveAs("Tiff", path);
    close();
}

makeLabels(inputs + "labels2d.tif", 64, 64, 1);
makeRaw(inputs + "centre2d.tif", 64, 64, 1, false, 1);
makeRaw(inputs + "rim2d.tif", 64, 64, 1, true, 1);
makeLabels(inputs + "labels3d.tif", 48, 48, 6);
makeRaw(inputs + "centre3d.tif", 48, 48, 6, false, 1);
makeRaw(inputs + "rim3d.tif", 48, 48, 6, true, 1);
for (s = 1; s <= 2; s++) {
    rim = false;
    if (s == 2) rim = true;
    makeLabels(inputs + "batch-labels/S" + s + "_labels.tif", 48, 48, 1);
    makeRaw(inputs + "batch-raw/S" + s + "_raw.tif", 48, 48, 1, rim, s);
}
print("SMOKE inputs written");

function requireFile(path) {
    if (!File.exists(path)) exit("SMOKE FAIL: missing output " + path);
}

// 3. Path-based single-image runs (2D with texture and classes, 3D with defaults).
out2d = work + "out2d/";
run("Object Intensity Profiling", "labels_path=[" + inputs + "labels2d.tif] "
    + "raw1_path=[" + inputs + "centre2d.tif] raw1_name=[A] "
    + "raw2_path=[" + inputs + "rim2d.tif] raw2_name=[B] reference=[A] "
    + "glcm texture_classes glcm_levels=16 texture_k=2 minimum_texture_voxels=16 "
    + "zernike profile_classes profile_k=2 radial_bins=6 "
    + "no_figures no_maps auto_save output=[" + out2d + "] hide_display");
requireFile(out2d + "Profiles/Per_Object_Profiles.csv");
requireFile(out2d + "Profiles/Object_Summaries.csv");
requireFile(out2d + "Profiles/Object_Zernike.csv");
requireFile(out2d + "Profiles/Profile_Classes.csv");
requireFile(out2d + "Texture/Object_Texture.csv");
requireFile(out2d + "Aggregate/Aggregate_Profiles.csv");
requireFile(out2d + "Aggregate/Profile_Class_Curves.csv");
print("SMOKE single-image 2D ok");

out3d = work + "out3d/";
run("Object Intensity Profiling", "labels_path=[" + inputs + "labels3d.tif] "
    + "raw1_path=[" + inputs + "centre3d.tif] raw1_name=[A] "
    + "raw2_path=[" + inputs + "rim3d.tif] raw2_name=[B] reference=[A] "
    + "auto_save output=[" + out3d + "] hide_display");
requireFile(out3d + "Profiles/Per_Object_Profiles.csv");
requireFile(out3d + "Aggregate/Aggregate_Profiles.csv");
figures = getFileList(out3d + "Figures/");
if (figures.length == 0) exit("SMOKE FAIL: no aggregate figures in " + out3d + "Figures/");
print("SMOKE single-image 3D ok (" + figures.length + " figure files)");

// 4. A two-sample folder batch.
outBatch = work + "batch/";
run("Object Intensity Profiling", "batch labels_folder=[" + inputs + "batch-labels] "
    + "labels_regex=[(.*)_labels\\.tif] raw1_name=[Signal] "
    + "raw1_folder=[" + inputs + "batch-raw] raw1_regex=[(.*)_raw\\.tif] "
    + "output=[" + outBatch + "] profile_classes profile_k=2 radial_bins=6 hide_display");
requireFile(outBatch + "Aggregate/Aggregate_Profiles.csv");
requireFile(outBatch + "Aggregate/Profile_Class_Curves.csv");
requireFile(outBatch + "Samples/S1/Profiles/Object_Summaries.csv");
requireFile(outBatch + "Samples/S2/Profiles/Profile_Classes.csv");
print("SMOKE batch ok");

print("SMOKE ImageJ " + getVersion());
print("SMOKE OK");
