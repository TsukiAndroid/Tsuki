# F-Droid preparation

Tsuki's Android application ID is `com.space4414.tsuki`. The F-Droid build uses
the `stable` product flavor.

## Build locally

Use JDK 21 and the Android SDK required by the project:

```sh
./gradlew -PfdroidBuild=true assembleStableRelease
```

With `fdroidBuild=true`, the release APK is intentionally unsigned for F-Droid
to sign, and the in-app GitHub APK updater is disabled. Optional crash reporting
remains consent-based. Do not use this flag for GitHub releases.

## GitHub release signing

Stable, Beta, Alpha, and tag-based GitHub release workflows require all four
production signing secrets: `KEYSTORE_FILE` (base64-encoded keystore),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

There is deliberately no debug-key fallback. If any secret is missing, the
release workflow stops before building. The production keystore is not stored
in this repository.

## F-Droid metadata

Submission metadata belongs in the separate
[`fdroiddata` repository](https://gitlab.com/fdroid/fdroiddata), not here.
Initial metadata should use application ID `com.space4414.tsuki`, source
repository `https://github.com/TsukiAndroid/Tsuki`, Gradle flavor `stable`,
and Gradle property `fdroidBuild=true`. The current version is 1.5.7
(version code 960), recorded in `app/version.properties`.

Example update-check configuration:

```yaml
UpdateCheckMode: Tags
UpdateCheckData: app/version.properties|VERSION_CODE=(\\d+)|VERSION_NAME=(.*)
```

Each F-Droid build must point to an immutable version tag and a full commit SHA.
Update `app/version.properties` in the same tagged source commit. Do not use
the moving `stable-latest` tag as an F-Droid source.

## External extensions

Before installing an executable extension from a third-party repository,
Tsuki displays a warning that F-Droid does not review or verify the downloaded
code and asks the user to confirm. Keep this confirmation in F-Droid builds.

## Remaining checks

Before submission, maintainers must complete F-Droid's dependency and bundled
asset license review, confirm upstream project lineage/rebranding details, and
run the build in the F-Droid build server. This preparation is not an approval
or guarantee of acceptance by F-Droid.


## External executable code and user consent

The app's built-in sources and bundled extensions are part of the APK. Optional third-party executable add-ons are separate and are never silently installed by the F-Droid build.

Before downloading, importing, or enabling executable third-party code, the app presents an opt-in disclosure with an equally available cancel action. The F-Droid-specific wording explicitly tells users that continuing bypasses F-Droid's checks for that add-on. This applies to Mihon APK extensions, JavaScript/Dart extensions from repositories or local files, and JAR plugins from GitHub or local files. The disclosure is also available from Settings → About.

Plugin update checks only notify the user that an update is available; they do not download or install updated plugin code automatically. The user must reopen the plugin flow and accept the disclosure before the updated JAR is downloaded and previewed.
