# Downloads

The release APK of **WhereQ-DOSBox**, for people who want to try the app
without building it.

**The APK is not in this repository.** CI builds it from the release tag and
publishes it as a release asset, so the file you download is the file that was
built, signed and verified — one artifact, one checksum, one place.

Older versions are on the [releases page](https://github.com/whereq/whereq-dosbox/releases)
from 1.0.0.9 on; the ones before that were never released there and live in the
repository's history, at their own tag — for example
`https://raw.githubusercontent.com/whereq/whereq-dosbox/v1.0.0.8/downloads/whereq-dosbox-1.0.0.8.apk`.

## The current release

| version | file | size |
|---|---|---|
| 1.0.0.18 | [`whereq-dosbox-1.0.0.18.apk`](https://github.com/whereq/whereq-dosbox/releases/download/v1.0.0.18/whereq-dosbox-1.0.0.18.apk) | 16 511 577 bytes (15.8 MB) |

* SHA-256 of the 1.0.0.18 APK:
  `80cc3cdfa54a3dff7296d298eef72fffa771c57a992c30bab1793c6f9a97d4d0`
* Signing certificate (SHA-256):
  `b16b480d5a1ff6d35baff0b15fa3fc4b0ee815e500861fbff1b062c9bf7d3933`

## What changed in 1.0.0.19

A **maintenance** release: the whole build toolchain moves to the current
stable line, and the app finally says the truth about the Android versions it
runs on.

* **The app runs on Android 6.0 or newer.** It always said so, and it did not:
  the manifest claimed API 21 while the native library was built for API 24,
  so the app really only started on Android 7.0. The library is now built for
  **API 23** and the manifest says 23 - the promise and the shipped binary
  agree for the first time, and the floor is one step lower than it has ever
  been.
* **The build toolchain is the current stable line.** AGP **9.5.3**, Gradle
  **9.9.2**, Kotlin **2.4.21**, the current AndroidX releases, NDK **r30**,
  CMake **3.31.6**, build-tools **37.0.0** and the API **37** platform. AGP 9
  compiles the Kotlin sources itself, so the `kotlin-android` plugin is gone
  from the build, and the DSL is the new one - properties instead of method
  calls. None of this reaches the phone: it is the tools that make the APK.
* **SDL2 2.32.10.** The Java shell is upstream 2.32.10 with exactly two
  deliberate deviations, both there to keep the app the way it is: the six
  `java.util.stream` calls in the audio-device code are plain loops, because
  `java.util.stream` only exists from API 24, and the activity keeps
  `SCREEN_ORIENTATION_FULL_SENSOR` instead of upstream's new `FULL_USER`,
  because this app is a landscape-first DOS machine and `FULL_USER` would
  freeze a game in portrait when auto-rotate is off.

Nothing else moves. The emulator core, the bundle launcher, the on-screen
keyboard, **Game Mode** and the save states are exactly what they were, and
the APK is signed with the same key, so it installs over 1.0.0.18 without
uninstalling it first: **every save state written by the earlier releases loads
normally**.

The **user guide** follows the floor and now asks for Android 6.0 or newer.

## What changed in 1.0.0.18

A release with a **feature** and four **fixes**: a hardware keyboard finally
reaches the emulator, the app can start DOSBox again instead of dying, the
tone that rode on the music is gone, and **Game Mode** comes back the way you
left it.

* **A Bluetooth keyboard works.** The vendored SDL2 had no path for Android key
  events, so a key pressed on a Bluetooth keyboard never reached the emulator:
  the on-screen keyboard was the only input there was. An Android key code is
  now mapped to a DOSBox key name and resolved through the same native table
  the on-screen keyboard uses, with **Shift**, **Ctrl** and **Alt** carried
  along, and only the first press of a held key forwarded - DOSBox does its
  own auto-repeat. Checked on a **real phone**: the letters and **Enter** pass
  the copy protection of *Mortal Kombat*, **F1** starts the game, **F10** opens
  its own options menu, and the arrow keys move the menu cursor.
* **DOSBox can start again in the same app run.** The devices of a run were
  never removed from the device table, so a second start in the same process
  ran out of slots and died with *DOS:Too many devices added*. Leaving the app
  with the Back button and opening it again was enough to hit it.
* **The tone that rode on the music is gone.** A steady beat tone sat on top
  of the in-game music on a real phone, and only while the music played. The
  shipped `dosbox.conf` now runs the mixer the way a phone wants it - at the
  device's native **48 kHz**, in blocks of **2048**, with a **50 ms**
  prebuffer - so the rate-tracking loop stops hunting and the warble with it.
  Audio buffering only: never game speed or logic. The analysis and its three
  diagrams are in `docs/AUDIO-FM-BEEP.md`.
* **Game Mode comes back the way you left it.** The profile, the D-pad mode
  and the remaps are restored in one pass, before the gamepad is built, so the
  chip that names the mode can no longer name the previous one.
* **The TURBO auto-fire cannot get stuck.** A rebuild while the FIRE button
  was held - a rotation, or the other thumb cycling a chip - detached the
  button without an `ACTION_UP`, so the key fired forever. The loop now lives
  with the game pad, and every path that releases the keys cancels it.

The **user guide** grew with it: the keyboard's **columns layout** and the
narrow-screen keyboard are pictured now, and the new **Game Mode** screenshots
were re-taken from the 1.0.0.17 release build.

The whole of this release - the hardware keyboard, the tone on the music, the
second start in the same app run, and **Game Mode** coming back the way it was
left - was checked on a **real phone**, not only in the emulator.

The save states, the bundle launcher, the on-screen keyboard and the emulator
core are unchanged: **every save state written by the earlier releases loads
normally**.

## What changed in 1.0.0.17

A **maintenance** release: two things in **Game Mode** that were wrong in a way
only the screen could show, and the **privacy policy**, which now lives in the
repository next to the code and the licences.

* **The FIRE button shows that it can be remapped.** While the remap mode is
  on, the buttons that can be remapped get an outline. The FIRE button is
  filled with the same gold as the outline, so the outline was invisible and
  the button looked like any other one. The outline now follows the button it
  marks: gold on the dark buttons, dark brown on the gold FIRE button.
* **The D-pad button names the mode you actually have.** The button in the
  bottom row of the left pad cycles the D-pad output between **ARROWS**,
  **NUMPAD** and **WASD**, and it carries the name of the current mode. When
  the app remembered a different mode for that game, the button kept the name
  of the mode the bar had been built with, so it could say *ARROWS* while the
  D-pad was sending *WASD*.
* **The privacy policy is in the repository.** The app collects no data and
  does not ask for the `INTERNET` permission, so it cannot send anything
  anywhere even if it wanted to. The policy says so in full, and now lives in
  the repository as `docs/PRIVACY.md` — next to `COPYING` and `NOTICE`, where
  the whole history of it is public. The README links to it, and the
  launcher's footer opens it next to the link to the licence screen.

The emulator core, the bundle launcher, the keyboard and the save states are
unchanged: **every save state written by the earlier releases loads normally**.

## What changed in 1.0.0.16

A **maintenance** release: the phone's **Back button works again**, the app
runs on the devices that need **16 KB memory pages**, and the **licence
travels with the app**.

* **The Back button works again.** From Android 13 on, the system stopped
  sending the back key to the activity, and SDL's surface swallowed what did
  arrive — so the button did nothing at all. The app now handles the back
  gesture itself, the way Android expects it to.
* **The presses walk out in order.** Back closes the launcher, then leaves
  **Game Mode**, then folds the keyboard panel, and only then leaves the app.
* **16 KB memory pages.** Every loadable segment of `libdosbox.so` is aligned
  for the 64-bit devices that require them (Android 15 and newer). CI checks
  it on every build now.
* **The DRAG lock no longer holds the mouse.** Leaving Game Mode while the
  **DRAG** button was locked used to leave the left mouse button *down* inside
  DOSBox — a game saw a click that never ended.
* **The licence travels with the app.** The app is GPLv2, so the licence and
  the copyright notices have to travel with the program: the full **GPL v2**
  text and the **NOTICE** are in the APK, and the launcher's footer links to
  them, next to the link to the complete source.

## What changed in 1.0.0.15

A release with a **feature**: **Game Mode**, a console-style gamepad for the
action games that want one.

* **Game Mode.** With the keyboard pads showing, switch **Caps Lock** on
  (double-tap **Shift**) and type the Konami code — **↑ ↑ ↓ ↓ ← → ← → B A** —
  and the pads flip into a gamepad: an eight-way D-pad, a trackball, and Y, X,
  CTRL, ALT, SPACE and ENT. The same code on the D-pad (then **X, Y**) takes
  you back, and the phone's Back button does it too.
* **The B and A must be capital.** The code is matched on the case the letters
  are actually typed in, so a lowercase *"ba"* just types *"ba"* into the DOS
  prompt.
* **Every button can be remapped.** Long-press the gold profile button and
  pick **Remap buttons…**. The remap belongs to that one game and is
  remembered.
* **The gamepad is a keyboard.** The gamepad sends the same SDL events the
  on-screen keyboard sends, so the guest cannot tell the two apart. The
  trackball sends the relative mouse events the SDL layer now accepts.
* **Leaving the app is clean.** A shutdown that failed used to abort the
  process with SIGABRT while it was only shutting down; the error is caught
  now.
* **The save-state format did not change.** The Game Mode is a new way to
  press the same keys, so every save state written by the earlier releases
  loads normally.

## What changed in 1.0.0.14

A **patch** release with three save-state repairs: the requests no longer
answer each other, a duplicated handle survives a restore, and a restore no
longer leaks a file descriptor.

* **A save that times out no longer answers the next request.** A save or a
  restore that overran its time limit used to hand its late result to the
  *next* request — the new request was answered with an old answer, and the
  file on disk could be the file the old request wrote. Every request now
  carries a generation, and a completion that arrives after its request gave
  up is dropped.
* **A duplicated handle survives a restore.** A handle duplicated with
  INT 21h/45h is two names for one file, and the restore keeps that sharing:
  the reference count goes back as it was saved, so closing one of the two
  names no longer takes the other down with it — which used to end in
  *"invalid handle"*.
* **A restore no longer leaks a file descriptor.** The machine a restore
  replaces is closed properly now, whatever its reference count. Before, a
  state in which a handle was shared left one host file descriptor open per
  restore, for the rest of the session.
* **The save-state format did not change.** All three fixes are in the
  snapshot's plumbing, not in the file, so every save state written by the
  earlier releases loads normally.

## What changed in 1.0.0.13

A **patch** release: the name and the mark of the project are on the app again,
and a restored save keeps the time it was made.

* **The app is called WhereQ-DOSBox.** The launcher name, the notification and
  the launcher icons all carry the name and the mark of the project. The
  package is `com.whereq.dosbox` and the download file is
  `whereq-dosbox-1.0.0.13.apk`, so the app installs under its own id and
  updates in place from the earlier releases.
* **A restored save keeps the time it was made.** Loading a save state
  rewrites the local file, and the launcher read the time off that file — so
  every restored slot claimed to be *"just now"*. The original save time is
  carried over now, and a slot that was made five hours ago still says *five
  hours ago* after it has been restored.
* **The save-state format did not change.** The fix is in the launcher, not in
  the snapshot, so every save state written by the earlier releases loads
  normally — the file layout is the same version 10 as before.

## What changed in 1.0.0.12

A **patch** on the `android-migration-complete` milestone: the mouse work that
closed the Android migration phase is what 1.0.0.11 shipped, and this release
repairs the packaging around it and moves the saves out of the app's own
storage. The milestone marker
`milestone/android-migration-complete` moves on to this release.

* **Your saves survive a reinstall.** The save-state slots lived in the app's
  private storage, and Android deletes that when the app is uninstalled — so
  reinstalling used to mean losing every save. Pick a folder on the phone
  (**Config → Saves folder**, or the offer that appears after the first save)
  and every slot is mirrored there, gzipped, next to the game itself. A
  reinstall, a new phone or a deleted app therefore all come back: the games
  are offered for restore by name, with their save states already there. Two
  archives with the same file name are told apart by name — *game* and *game
  (2)* — so their saves never end up in the same place.
* **The save state keeps the mouse driver's configuration and none of its
  run-time state.** 1.0.0.11 put the *whole* INT 33h driver into the snapshot
  — the event queue, the click counters and the interrupt re-entrancy flags
  included. Restoring that wedged mouse-driven games: the stale queue and
  flags fed garbage into the INT 74 handler and into the game's own callback,
  and a game could hang on load. The snapshot now carries only the
  configuration the guest cannot rebuild itself — the game's event-callback
  registration, the cursor range and position, the cursor shape and hotspot,
  the granularity and the sensitivity — and the volatile state is left clean,
  so a restored game starts from an idle driver. The cursor shape matters too:
  without it the visible cursor reverts to the default block and no longer
  lines up with the real click point.
* **The emulator knows its own version.** The version was a literal `1.0.0.1`
  in the Android config template, so the DOS startup banner and the window
  title said *WhereQ-DOSBox version 1.0.0.1* no matter which release was
  installed, the BIOS machine string said 1.0.0.1, and the mapper file was
  always `mapper-1.0.0.1.map`. The native build now reads the same `VERSION`
  file that the release script writes and the app reads, so the version in the
  banner and the version on this page cannot disagree.
* **A local build ships all three ABIs.** `android/build-android.sh` and
  `android/build-sdl2.sh` defaulted to two of them, so an APK built from a
  plain `./bin/build.sh apk` did not carry the same ABI set as the published
  one. Both now build `arm64-v8a`, `armeabi-v7a` and `x86_64` by default — the
  same set CI builds.

**Save states written by 1.0.0.11 and by the earlier 1.0.0.12 cuts cannot be
loaded by this build**: the state file grew a version when the mouse block
changed, and an older state is refused rather than misread. Save states
written by this build load normally.

One consequence of the volatile half staying out: the button state is not
captured, so a restored game starts with the mouse released and a click that
was held down across the save has to be pressed again.

**This release was re-cut.** The tag still reads `v1.0.0.12`, but the file
behind the download link is a different build than the one first published
under that name, so its checksum is a different one. The checksum at the top of
this page is the current one.

One consequence of the slimmed block: the button state is no longer captured,
so a restored game starts with the mouse released and a click that was held
down across the save has to be pressed again.

## What changed in 1.0.0.11

* **The mouse has a right button.** A DOS mouse has two buttons and a
  touchscreen has one, so there was no way to send a right click — and in most
  DOS games the right button is *cancel* or *back*. The on-screen keyboard now
  has an **RMB** key on the left pad, next to **Tab**, and a **second finger**
  on the picture does the same at that finger's own position.
* **A click is a held button, not a tap.** The first finger holds the **left**
  button for as long as it is down, so a drag still drags, and the right button
  is held the same way. This is what a game that reads the mouse *live* needs:
  the button state at INT 33h AX=3. A click that was over in a millisecond
  could fall between two polls and be missed altogether.
* **The mouse survives a save state.** The INT 33h driver keeps its state
  outside the guest's memory — the game's event-callback registration, the
  cursor range and the button state. It was not in the snapshot, so after a
  restore the cursor still moved but the game's clicks stopped reaching it: a
  dialog's *confirm* button did nothing. The driver is now part of the state.

**Save states written by 1.0.0.10 cannot be loaded by this build.** The state
file grew a version when the mouse driver joined the snapshot, and an older
state is refused rather than misread.

This release carried the **milestone tag** for the end of the Android
migration phase — `milestone/android-migration-complete`, the same marker that
was placed at 1.0.0.10 and has since been moved on to 1.0.0.14.

The rest of the emulator core is unchanged — this release is the app layer and
the mouse driver.

## What changed in 1.0.0.10

* **The sound survives a save state.** The FM chip is now snapshotted
  *exactly* — the envelope, the wave phase and the LFO counter all come back
  with the state. In 1.0.0.9 the chip was not in the snapshot at all: the music
  kept playing while the rest of the machine was rewound, and it was gone
  entirely when a state was restored into a fresh process. Now the sound comes
  back with the machine, in the middle of the note rather than at the
  beginning of it. The PC speaker comes back silent instead of droning on its
  own.
* **A key no longer sticks.** Rotating the phone while a key was held left
  that key pressed in DOSBox for ever. Every key that is down is now released
  when the layout is rebuilt, and a modifier that is shown in two places (the
  pad and the fold-up panel) counts its fingers instead of assuming there is
  only one.
* **The launcher fits on the screen.** The **Files** panel measures the space
  it has and caps the game list to it, so a third game no longer pushes its
  buttons off the bottom edge where nothing could bring them back.
* **The desktop build links again.** The save-state code was missing from the
  autotools build and the two archives had a circular dependency — the
  desktop baseline compiles and links again.
* **A technical reference.** `docs/TECHNICAL-REFERENCE.md` in the repository
  describes the whole implementation with the diagrams: the native build, the
  save-state machine, the exact FM snapshot, the bundle launcher, the
  on-screen keyboard, the throughput benchmark, what is still open and the
  screenshot gallery.

**Save states written by 1.0.0.9 cannot be loaded by this build.** The state
file grew a version when the sound chips became part of the snapshot, and an
older state is refused rather than misread.

This release carried the **milestone tag** for the end of the Android
migration phase; the marker has since moved on to 1.0.0.14, which carries the
same state. What follows is maintenance: bug fixes and documentation.

## What changed in 1.0.0.9

* **The keyboard is a console now.** In landscape the keys sit in the two
  black bars beside the picture, the way a handheld console is built: the
  picture in the middle, one hand on each side, nothing on top of the
  picture. The **left pad** holds the arrow keys as a D-pad (up on top, left
  and right side by side, down below) and the left half of a split QWERTY,
  with **File**, **Esc** and **Tab** along the top. The **right pad** holds
  the action keys — **Alt**, **Ctrl**, **Shift**, **Space**, **Enter** — and
  the right half of the QWERTY, with **#+=**, **Fn** and Backspace along the
  top.
* **Three layers, one key each.** **#+=** swaps both halves for the
  punctuation, **Fn** for F1–F12, Ins/Del, Home/End, PgUp/PgDn, Caps, PrtSc
  and Pause. The switched key lights up while its layer is on. The letter
  layer of the right half already carries `: . / \`, so a DOS path needs no
  layer switch at all.
* **Modifiers work both ways.** *Tap* Ctrl, Alt or Shift and it stays armed
  for the next key — Ctrl, then C. *Hold* it and it behaves like a real key
  for as long as the finger is down, which is what a game expects from its
  fire and strafe keys while the other thumb steers.
* **The layout follows the screen.** A letterbox bar of at least 116 dp gets
  the full pads; a narrower one gets a single key column per side and the
  fold-up QWERTY; portrait gets a slim strip along the bottom. The letter
  keys come to about 33 dp wide on a 20:9 screen and about 24 dp on an 18:9
  one — tight on the narrowest phones, where the D-pad and the action keys
  keep their full size.

The emulator core is the one from 1.0.0.8, unchanged — the whole change is
in the app layer.

## What changed in 1.0.0.8

* **Save states.** Every game gets three slots. **States** in the Files panel
  opens them; **Save** freezes the whole running machine — the CPU, the DOS
  memory, the video memory, the open DOS file handles, the hardware timers —
  and **Load** jumps back to that exact moment. A slot is a file on the phone,
  so it survives quitting the app and rebooting the phone.
* **Resume resumes.** The **Resume** button used to start the last game from
  the beginning. It now restores its newest save state, so the player
  continues where they left off. With no save state the game starts from the
  beginning, as before.
* **The arrows are an inverted T.** In landscape the four arrow keys sit in
  the right-hand column the way they do on a real keyboard — up on top, left
  and right side by side, down below.
* **The game keeps running.** While a game is running, a small ongoing
  notification holds the emulator process, so switching to another app no
  longer loses the game. Tap the notification to come back to the same frame.

Some old DOS programs do not survive a restore — the extender of Ultima VIII
still aborts on it. That is a limitation of the DOS internals the snapshot
does not capture yet, not of the app; `docs/TECHNICAL-REFERENCE.md` has the
details.

## What changed in 1.0.0.7

* **The CPU core can be changed in the app.** The **Config** button in the
  Files panel offers the five cores DOSBox knows: `auto` (the default),
  `normal`, `dynamic`, `simple` and `prefetch`. In 1.0.0.6 the fallback to
  `core=normal` was only documented — the configuration file is regenerated
  on every boot, so it could not be edited on the phone.
* The keyboard of that release is two columns in the letterbox bars
  (the modifiers on the left, **File**, **Ent**, **Space** and the arrows on
  the right). The README shows it.

## What changed in 1.0.0.6

The CPU core is now `core=auto`. A program that stays in real mode runs on the
interpreter, exactly as before; a program that switches into protected mode
hands over to the recompiling (JIT) core, which is compiled into the native
library for all three ABIs. Both paths were verified on the emulator —
STARFALL in real mode, Ultima VIII in protected mode.

If the JIT code cache cannot be allocated, `core=normal` is the fallback —
from 1.0.0.7 on it can be picked in the app, see above.

## Installing it

1. Copy the apk to the phone (cable, cloud drive, whatever is at hand).
2. Open it with the Files app.  Android asks whether to allow installing
   unknown apps from that app — allow it.
3. Install, then open **WhereQ-DOSBox**.

The APK is signed with the release key of this repository, so it can be
updated in place: a later version replaces it without uninstalling first.

The SHA-256 of the current release is at the top of this file; every release
carries its own `.sha256` sidecar next to the APK on the [releases
page](https://github.com/whereq/whereq-dosbox/releases).

## What to try first

The app starts in a DOS prompt.  The **File** button in the on-screen
keyboard opens the launcher, where a bundle can be installed — and
[`examples/game-bundle`](../examples/game-bundle) holds **STARFALL**, a small
arcade game that shows what a bundle looks like when it runs.  Its bundle
zip is [`whereq-demo-bundle.zip`](../examples/game-bundle/dist/whereq-demo-bundle.zip);
install it with *Install a bundle (.zip)*.

To hear the save-state fix for yourself, install
[`examples/opl-test-bundle`](../examples/opl-test-bundle): it programs one
sustained tone on the FM chip and then waits for a key.  Save a state while it
plays, wait a couple of minutes, load the state — the tone continues from the
middle of its attack instead of starting over, and the log lines written by the
save and by the load are identical.

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

A local build is for your own phone. The APK people download is built by CI
from the release tag, so the published file is the one that was signed,
verified and announced — see [docs/TECHNICAL-REFERENCE.md](../docs/TECHNICAL-REFERENCE.md)
for the details.
