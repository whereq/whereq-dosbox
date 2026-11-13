STARFALL - the WhereQ-DOSBox example game
=========================================

A tiny arcade game written in 16-bit DOS assembly.  It shows the three
things that matter on a phone:

  * graphics - VGA mode 13h, 320x200, drawn straight into the video memory
  * keyboard - the BIOS keyboard buffer, read with INT 16h
  * audio    - the PC speaker, driven through the PIT counter 2

The keys:

  arrow keys or W A S D   move the ship
  space                   fire
  escape                  back to the DOS prompt

How it was built:

  The whole game is one .COM file of about 5 KB, with no runtime and no
  libraries.  The source is 16-bit x86 assembly:

      as --32 -o starfall.o starfall.asm
      ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start -o starfall.com

  The text is drawn with an 8x8 bitmap font that is generated from a
  TrueType font, and the star field is dithered so that the night sky does
  not show up as bands.

Have a look at whereq.com for WhereQ-DOSBox itself.
