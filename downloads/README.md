# Downloads

The release APK of **WhereQ-DOSBox**, for people who want to try the app
without building it.

| file | version | size |
|---|---|---|
| [whereq-dosbox-1.0.0.5.apk](whereq-dosbox-1.0.0.5.apk) | 1.0.0.5 | 15.5 MB |
| [whereq-dosbox-1.0.0.4.apk](whereq-dosbox-1.0.0.4.apk) | 1.0.0.4 | 5.0 MB |
| [whereq-dosbox-1.0.0.3.apk](whereq-dosbox-1.0.0.3.apk) | 1.0.0.3 | 5.0 MB |

## Installing it

1. Copy the apk to the phone (cable, cloud drive, whatever is at hand).
2. Open it with the Files app.  Android asks whether to allow installing
   unknown apps from that app — allow it.
3. Install, then open **WhereQ-DOSBox**.

The APK is signed with the release key of this repository, so it can be
updated in place: a later version replaces it without uninstalling first.

* SHA-256 of the 1.0.0.5 APK:
  `562598cdf2ca9214f50af94604843acba8af90c292eeb7e06320532b313e4ac4`
* Signing certificate (SHA-256):
  `b16b480d5a1ff6d35baff0b15fa3fc4b0ee815e500861fbff1b062c9bf7d3933`

## What to try first

The app starts in a DOS prompt.  The **File** button in the on-screen
keyboard opens the launcher, where a bundle can be installed — and
[`examples/game-bundle`](../examples/game-bundle) holds **STARFALL**, a small
arcade game that shows what a bundle looks like when it runs.  Its bundle
zip is [`whereq-demo-bundle.zip`](../examples/game-bundle/dist/whereq-demo-bundle.zip);
install it with *Install a bundle (.zip)*.

## Building it yourself

The APK is built from this repository with Gradle:

```sh
WHEREQ_RELEASE_KEYSTORE="$PWD/build/release.keystore" \
WHEREQ_RELEASE_STORE_PASS='<the release key password>' \
WHEREQ_RELEASE_ALIAS=whereq \
    ./bin/build.sh apk --release
```

Without the release key the Gradle build falls back to the debug key, and the
resulting APK is *debuggable* — it cannot be installed over a release-signed
one (Android refuses it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

See [porting/ANDROID-PORT.md](../porting/ANDROID-PORT.md) for the details.
