# The example bundle

This folder holds **STARFALL**, a tiny arcade game that is the example bundle
for [WhereQ-DOSBox](../../README.md).  It exists so that a new user can see
what a bundle is, how one is installed on the phone and what it looks
like when it runs — without having to find a game first.

The game is deliberately small and it is deliberately written in the oldest
and most portable way there is: 16-bit x86 assembly for plain DOS, one
`.COM` file, no runtime, no libraries.  Everything it does goes through the
same BIOS calls that any DOS game from 1993 would use, which is exactly the
point: if it runs well on the phone, then the phone port of DOSBox is doing
its job.

```
examples/game-bundle/
├── build.sh          build the game and pack the bundle zip
├── bundle/           the files that go into the bundle
│   ├── PLAY.BAT      the start-up script the launcher looks for
│   ├── README.TXT    what the bundle is, shown to the user
│   └── STARFALL.COM  the game (created by build.sh)
├── src/
│   ├── starfall.asm  the whole game
│   ├── font8x8.inc   the 8x8 bitmap font (generated)
│   └── font8x8.png   a proof sheet of the font
├── tools/
│   └── mkfont.py     makes font8x8.inc out of a TrueType font
├── build/            the object file and the .COM (created by build.sh)
└── dist/
    └── whereq-demo-bundle.zip   the bundle you install on the phone
```

## Building it

```
./build.sh
```

The script sources the repository toolchain (`../../toolchain/env.sh`, which
has to be *sourced*, not run) and then:

```
as --32 -I src -o build/starfall.o src/starfall.asm
ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start -o build/STARFALL.COM build/starfall.o
```

The image is linked at `100h` because that is where DOS loads a `.COM` file
and enters it, so every label in the source is also its offset inside the
loaded segment.

The font is generated, not hand written:

```
python3 tools/mkfont.py src/font8x8.inc
```

`mkfont.py` renders the printable ASCII range of Liberation Mono into an 8x8
bitmap and writes one `.byte` per pixel row, most significant bit leftmost.
It also writes `src/font8x8.png`, a proof sheet, so that the font can be
looked at without running anything.

The result is packed into `dist/whereq-demo-bundle.zip`, which holds
`PLAY.BAT`, `STARFALL.COM` and `README.TXT`.  The file names are upper case
and short, because DOS 8.3 names are what the launcher looks for.

## Installing it on the phone

Copy the zip to the phone (anywhere the Files app can see it) and open it
with WhereQ-DOSBox, or use the **File** button in the app and pick
*Install a bundle (.zip)*.

The launcher unpacks the bundle into `C:\GAMES\<name>\` and finds
`PLAY.BAT` as the entry point, so the game starts with one tap on **Play**.

## What the game does

| | |
|---|---|
| graphics | VGA mode 13h, 320x200, drawn straight into the video memory, a dithered sky, sprites, a score bar |
| keyboard | the BIOS keyboard buffer, read with INT 16h: arrow keys or W A S D to move, space to fire, escape to quit |
| audio | the PC speaker, driven through the PIT counter 2: a tune on the title screen and a short beep for every shot |

The screen is never repainted as a whole.  The sky, the score bar and the
ground are painted once, and after that only the moving parts of the picture
are touched: a star is erased where it was and drawn where it is now.  The
colour of the sky is a function of the row alone (`bg_colour`), so erasing a
sprite is just a fill of the right colour.  A phone is not a fast computer
and this is what keeps the game at a playable speed.

## Notes for anyone reading the source

* The file is a `.COM` image, so there is no loader and no header: DOS jumps
  to offset `100h` of the segment and the code starts there.
* `mov si, label` loads the *contents* at the label; `mov si, offset label`
  loads the address.  All the data is addressed with `offset`.
* Numeric local labels (`1:`, `2f`, `3b`) are used for the inner loops.
* A variable that is read with a word instruction has to be a word, because a
  word read of a byte variable also reads the byte that follows it.  This is
  why `txt_scale`, `blk_scale` and `g_bits` are words.
* `CX` is the counter of `loop`, so it is saved *before* anything else puts a
  value in `CL` — and also before a *nested* loop puts a value in `CX`.  The
  first version of the game used `CX` for a coordinate inside `check_hits` and
  `update_booms`, which are themselves counted with `loop`.  The counter was
  then the coordinate, the loop ran thousands of times instead of four, and the
  game scribbled over its own memory.  On the phone that showed up as the game
  jumping back to the DOS prompt in the middle of a round.
* The round is started from two places: the title screen and the game over
  screen.  Both call `start_game`, and both jump into the game loop
  afterwards — without that jump the game would sit on the game over screen
  and wait for a key forever.
* The wait between two frames is `INT 15h/86h`.  DOSBox counts that wait in
  milliseconds (it takes 997 off the count on every RTC interrupt, and the
  interrupt comes about 600 times a second), so the count in `CX:DX` is
  roughly a millisecond, not the microsecond a real BIOS would use.
