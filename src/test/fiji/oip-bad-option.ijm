// A run with an unknown option must stop with a one-line message.
// Run it through src/test/fiji/run-smoke.sh.
setBatchMode(true);
run("Object Intensity Profiling", "labels_path=[missing.tif] raw1_path=[missing.tif] mystery_flag");
print("BAD OPTION ACCEPTED");
