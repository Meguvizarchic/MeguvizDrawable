# Candidate ABI baseline

`tools/dump_abi.py` reads a built JAR or AAR, including its `classes.jar` and
embedded `libs/*.jar`, and records public/protected classes and members with
JVM descriptors and constant values. It includes visible nested types and
checks the visibility of their enclosing types. Duplicate classes and
multi-release JARs are rejected rather than silently choosing a definition.

Use Python 3.10 or newer and JDK 17 with `javap` on PATH. Generate from the exact staged
artifacts; example for PowerShell:

```powershell
python tools/dump_abi.py build/staging-maven/io/github/jichuo1/meguviz/meguviz-drawable-core/0.1.0-dev.1/meguviz-drawable-core-0.1.0-dev.1.jar --output docs/abi/0.1.0-dev.1/core-api.txt --metadata docs/abi/0.1.0-dev.1/core-api.json
python tools/dump_abi.py build/staging-maven/io/github/jichuo1/meguviz/meguviz-drawable-android/0.1.0-dev.1/meguviz-drawable-android-0.1.0-dev.1.aar --output docs/abi/0.1.0-dev.1/android-api.txt --metadata docs/abi/0.1.0-dev.1/android-api.json
python tools/test_dump_abi.py
```

The metadata records artifact and embedded-JAR SHA-256, API text SHA-256,
class count and `javap` version without machine paths. API rendering can vary
across JDK versions; use the recorded JDK for comparison. The tool snapshots
the supplied bytes before inspection, so the receipt identifies exactly what
was scanned. Test inputs exercise public/protected nested classes, hidden
enclosing classes, member visibility and stable JAR/AAR output.

This first candidate is a baseline, not proof of compatibility with an older
release. A future compatibility claim also needs old consumer binaries built
against the earlier artifact and run without recompilation on the new one.
This Java-level dump does not validate Kotlin metadata, runtime behavior,
Android API availability, shrinking or dynamic providers.

`0.1.0-dev.2/`, `0.1.0-dev.3/`, `0.1.0-dev.4/` and `1.0.0/` are separate later candidate snapshots;
`1.0.0/` only adds to the `0.1.0-dev.4` API text. `1.0.0/core-api.*` is taken from the jar released
on Maven Central; `1.1.0/` is an unreleased **historical candidate snapshot**; 1.1.0 was never published and has been superseded by 1.2.0. It only adds API (`BindingScope.claim`, `FlightCache`, opt-in `StateMatcher.prepare` / `PreparedMatcher`, `MainThreadPoster`, `BitmapWeigher`); nothing was removed or changed against 1.0.0. The current baseline for release decisions is `1.2.0/`; the `1.1.0/` directory is kept for the frozen checksum receipt chain and is not a published compatibility claim. `1.0.0/` also holds first dumps of the four optional
bridge artifacts (`compose`, `meguviz-android`, `meguviz-ui`, `lumen`); they are baselines, not compatibility claims.
Preserve the earlier directories instead of replacing them. The frozen `fixtures/abi/LegacyCoreClient`
exercises a small old core API subset without recompiling; it is not an
exhaustive compatibility guarantee.

`1.2.0/core-api.*` (2026-11-08, joint round 1) is generated from the staging core jar of branch
`perf/joint-r1` (artifact SHA-256 in the JSON, `javap` 21.0.12.1). Against `1.1.0/core-api.txt` it only
adds `DrawingRecipe.of`, `withStroke` and `withMetrics`; nothing was removed or changed. `LegacyCoreClient`
compiled against the Central 1.0.0 jar ran unchanged on this jar (PASS). The android API is unchanged in this
round and was not re-dumped (its staging publish needs Dokka, unavailable offline).
