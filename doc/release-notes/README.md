# Release notes

The same scheme as the desktop repository (fear/doc/release-notes). One file per release, `vX.Y.Z.md`, is the only place its GitHub Release text
is written. The **Release notes** workflow (`.github/workflows/release-notes.yml`)
creates the release from it when the tag is pushed, and updates the release
whenever the file changes - so a description is corrected by editing the file,
never on the web page.

## Format

The file reads like an annotated tag message: the first line is the title,
then a blank line, then the description.

```
v0.6.0 – Key rotation, metadata privacy, group calls

One paragraph: what this release is about, in two or three sentences.

Added:
- One line per change, in plain words; wrap at about 72 columns and
  indent the continuation by two spaces.

Changed:
- ...

Fixed:
- ...

Security:
- ...

Known limitations:
- ...

Downloads: the APK and its signing certificate (SHA-256
c7b89abf8ff8e1b661ace6a737b8cdb92d88e18bf55d4c96d949048d6fe2ffdd).

Compatibility: what has to be updated together, if anything.

Desktop: https://github.com/shchuchkin-pkims/fear/releases/tag/v0.6.0
```

Rules that keep the list uniform:

- **Title**: `vX.Y.Z – Theme` with an en dash, the theme in sentence case and
  short: what a reader would call this release.
- **Sections** appear only when they have something, in this order: `Added`,
  `Changed`, `Fixed`, `Security`, `Known limitations`. A security release
  groups its fixes by severity instead (`Critical`, `High`), as v0.5.1 does.
- **Plain text**: no headings, no bold, no emoji. Each bullet says what a
  user or an operator notices, with numbers where they exist.
- **The last line** links the release of the other repository (desktop ↔
  Android) when one exists.

## Releasing

1. Start from the commits since the last tag:
   `python3 .github/scripts/release-notes.py draft vX.Y.Z > doc/release-notes/vX.Y.Z.md`,
   then write it up and delete the commit list at the bottom.
2. Commit the file, then tag - with the same text, so the tag carries it too:
   `git tag -a vX.Y.Z -F doc/release-notes/vX.Y.Z.md && git push origin vX.Y.Z`.
3. The workflow names and describes the release. The APK is attached by hand:
   CI has no release key, and the in-app updater installs the first .apk it
   finds, so only the release-signed one may be there -
   `./gradlew assembleRelease`,
   `cp app/build/outputs/apk/release/app-release.apk fear-android-vX.Y.Z.apk`,
   `gh release upload vX.Y.Z fear-android-vX.Y.Z.apk`.
