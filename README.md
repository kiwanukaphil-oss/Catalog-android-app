# Catalog Android App

K-Line's native Android capture and recovery pilot, maintained separately from the [web catalog](https://github.com/kiwanukaphil-oss/Kline-image-catalog).

- [Build, run and test instructions](android/README.md)
- [Roadmap and completed checks](docs/android-roadmap-checklist.md)
- [API contract](docs/android-api-contract.md)
- [Prototype verification](docs/android-prototype-report.md)

The Android project lives in `android/`. Design documents live in `docs/`, and saved verification reports live in `verification/android-pilot/`.

This is an isolated capture pilot. Its fixture service does not connect to production stock. See the roadmap for the remaining integration work.

The Android work was separated from the catalog workspace on 13 September 2026. Local build caches, device captures, fixture data and SDK settings were moved with the workspace but are excluded from Git.
