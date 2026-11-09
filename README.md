<div align="center">

<img src="docs/brand/MeguvizDrawable.svg" width="96" height="96" alt="MeguvizDrawable">

# MeguvizDrawable

**面向 Android 的 Drawable 库（开发中）**

独立 Java core · Android / Compose 模块 · 家族桥接

<br>

[![License](https://img.shields.io/badge/license-Apache--2.0-00AEEC?style=flat-square&labelColor=2d3a55)](LICENSE)
[![Language](https://img.shields.io/badge/lang-Java-DEA584?style=flat-square&labelColor=2d3a55)](https://github.com/Meguvizarchic/MeguvizDrawable)
[![Issues](https://img.shields.io/github/issues/Meguvizarchic/MeguvizDrawable?style=flat-square&labelColor=2d3a55)](https://github.com/Meguvizarchic/MeguvizDrawable/issues)

[License](#license)

</div>

---

<details>
<summary><b>目录</b></summary>

- [License](#license)

</details>

---

MeguvizDrawable is an Android drawable library under development. Its packages
use the `io.github.jichuo1.meguviz.drawable` family namespace. The core is a
standalone Java module; the Android module uses the core without requiring
MeguvizAndroid, MeguvizUI, MeguvizRef, or Lumen. The current API is provisional.
`1.0.0` core/android are published to Maven Central and immutable. The current development
version is `1.2.0` (unpublished on Central). A local, unsigned `meguviz-bom:1.2.0` candidate
exists only in the maintainer's cross-library workspace (it is not part of this repository), for
cross-library integration only. See `docs/engineering/RELEASE.md`.

With JDK 17, run `./gradlew check` (Windows: `gradlew.bat check`) for the host
profile. To include the Android module use
`./gradlew -Pmeguviz.profile=android check` with an Android SDK installed.
Android, device, BIL integration and
performance gates are tracked separately and have not passed yet. CI only checks, with
`tools/check_abi_superset.py`, that the public Java signatures of the published `1.0.0` are still present
(see [docs/abi](docs/abi/README.md)); that says nothing about behavior. The latest
feature changes are pending the planned consolidated verification.

The current Android implementation accepts an immutable `DrawingRecipe` and a
fixed density. It handles basic shapes, solid or stateful colors, linear
gradients and centered strokes; its platform behavior has not yet been
verified. Nonzero optical insets are explicitly unsupported; bounds that cannot
represent the requested stroke or gradient are rejected during preparation.
More complex paths, dynamic metrics, adapters and published coordinates are
still pending.

An experimental, explicitly registered shape-provider API supports bounded
closed polygons through a separate Android drawable. Its supported operations
and independent consumer fixture are described in
[fixtures/provider](fixtures/provider/README.md); this API is not yet stable.

`OwnedDrawableWrapper` is a single-owner, closeable adapter for an unattached
external drawable. Animated children require an already attached host callback;
children that schedule during preparation are rejected, and the host must hide
or close the wrapper before replacing its callback. API 29 BlendMode tint is
not forwarded. See the class contract for these boundaries.

Optional bridges are separate artifacts and none is required by the base library: Compose (`Painter` over
the same Canvas backend), MeguvizAndroid (lifecycle, resource and frame authorities), MeguvizUI (exclusive
background property) and Lumen (surface bindings under drawable-scope ownership). They are built with
`-Pmeguviz.profile=bridges`. Their upstream versions, the behavior observed on the real artifacts and the
limits are in [bridges](docs/engineering/BRIDGES.md); the Lumen bridge is not in the Maven Central bundle because
it needs a Lumen release that is not on Central yet: Central has only `io.github.jichuo1:lumen-engine:1.6.1`, whose
AAR metadata requires compileSdk 37.2, and the fix (Lumen source `1.6.2`) is unpublished.

See [engineering status](docs/engineering/STATUS.md) and
[baseline](docs/engineering/BASELINE.md) for current progress and limits;
[verification](docs/engineering/VERIFICATION.md) records the initial check.

## License

[Apache License 2.0](LICENSE).
