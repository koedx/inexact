# InExact — Milestone 0 skeleton assembly

This zip contains only the Gradle/build/CI scaffolding and the ProvisionActivity
stub. Two source pieces are NOT included and must be dropped in:

## 1. The AOSP calculator source and resources (you already have these)

From the verified `ExactCalculator` tree (commit 7265aa3), copy:

- `src/`  ->  `app/src/main/java/`
  (so the files land at `app/src/main/java/com/android/calculator2/*.java`)
- `res/`  ->  `app/src/main/res/`
- `assets/`  ->  `app/src/main/assets/`   (licenses.html)

Do NOT copy the AOSP `AndroidManifest.xml` — this skeleton ships the modified one.
Do NOT copy `Android.mk`, `CleanSpec.mk`, or `proguard.flags` — the Gradle files
replace them (proguard.flags is already imported here as `app/proguard-rules.pro`).

## 2. The constructive-reals library (fetch + verify)

The calculator depends on `com.hp.creals` (the AOSP `cr` static library), which is
in a separate AOSP repo and is not a Maven artifact:

    git clone https://android.googlesource.com/platform/external/crcalc

If the clone's default branch is empty (as happened with ExactCalculator), check
out a pre-deprecation commit or an android-9 era tag instead. Then copy the
package into the app source tree:

    app/src/main/java/com/hp/creals/*.java

(Files: CR.java, UnaryCRFunction.java, StringFloatRep.java. The abort/overflow
exceptions are nested classes inside CR.java, not separate files.)

Recommended: zip `src/com/hp/creals/` and upload it in the chat for verification
before placing it, mirroring how the calculator source was checked.

## Final tree (relevant parts)

    InExact/
    ├── settings.gradle
    ├── build.gradle
    ├── gradle.properties
    ├── .github/workflows/build.yml
    └── app/
        ├── build.gradle
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/licenses.html
            ├── res/ ...
            └── java/
                ├── com/android/calculator2/*.java          (AOSP source)
                ├── com/android/calculator2/gate/ProvisionActivity.java
                └── com/hp/creals/*.java                     (vendored cr)

## Before the first build: repo secrets

Generate the InExact release key (per-app key), then add these repo secrets
(Settings -> Secrets and variables -> Actions):

- SIGNING_KEYSTORE_B64   (base64 of the .jks)
- SIGNING_STORE_PASSWORD
- SIGNING_KEY_ALIAS       (inexact)
- SIGNING_KEY_PASSWORD

The workflow decodes the keystore to a temp file at build time and deletes it
afterward; no key material is committed.
