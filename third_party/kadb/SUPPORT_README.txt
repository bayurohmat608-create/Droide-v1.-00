Kadb 2.1.4 runtime support set (reviewed 2026-09-18)

Current production support artifacts are pinned under third_party/maven and are verified by
third_party/ARTIFACTS_SHA256.txt plus third_party/kadb/EMBEDDED_SHA256SUMS.txt.

The former com.github.Flyfish233:spake2-java:1.1.1 GPL runtime dependency was removed from
the production graph. It is replaced by the dependency-free, source-included
com.baystudio.compat:spake2-boringssl-compat:1.0.0 component (BSD-3-Clause).

The replacement's deterministic build/vector verifier is tools/kadb/build_spake2_compat.py.
Physical-device ADB pairing interoperability and independent cryptographic/side-channel review
remain release blockers; this file does not claim those checks have passed.
