# ===========================================================================
#  STARFALL - the WhereQ-DOSBox example game
# ===========================================================================
#
#  A tiny arcade game written in 16-bit x86 assembly for plain DOS.  It is
#  the example game bundle that ships with WhereQ-DOSBox, so it has to show
#  the three things the phone port has to get right:
#
#    graphics - VGA mode 13h, 320x200, drawn straight into video memory
#    keyboard - the BIOS keyboard buffer, read with INT 16h
#    audio    - the PC speaker, driven through the PIT counter 2
#
#  Build it with ./build.sh, which produces STARFALL.COM and the bundle
#  zip that WhereQ-DOSBox installs.
#
#  The keys:
#
#    arrow keys / W A S D   move the ship
#    space                  fire
#    escape                 back to the DOS prompt
#
#  The file is a .COM image: DOS loads it at offset 100h of its segment and
#  enters it there, so the image is linked at 100h and every label in here
#  is at the same offset inside the loaded segment.
#
#  How it draws
#  ------------
#  A DOSBox on a phone is not a fast computer, so the game never repaints
#  the whole screen.  The sky, the score bar and the ground are painted once
#  (draw_background) and after that only the moving parts of the picture are
#  touched: a star is erased where it was and drawn where it is now.  The
#  colour of the sky is a function of the row alone (bg_colour), so erasing
#  a sprite is just a fill of the right colour.
# ===========================================================================

        .intel_syntax noprefix
        .code16
        .text
        .globl _start

# --- the screen ------------------------------------------------------------
        .equ SCR_W,      320
        .equ SCR_H,      200
        .equ HUD_H,      14          # the score bar at the top
        .equ SKY_TOP,    14          # the first row of the sky
        .equ GROUND_Y,   172         # the first row of the ground

# --- the palette -----------------------------------------------------------
        .equ C_BLACK,    0
        .equ C_SKY1,     1           # the night sky, from dark to bright
        .equ C_SKY2,     2
        .equ C_SKY3,     3
        .equ C_SKY4,     4
        .equ C_GREEN,    5
        .equ C_LGREEN,   6
        .equ C_YELLOW,   7
        .equ C_ORANGE,   8
        .equ C_RED,      9
        .equ C_PINK,     10
        .equ C_MAGENTA,  11
        .equ C_WHITE,    12
        .equ C_DGREY,    13          # the score bar
        .equ C_GREY,     14          # the ground
        .equ C_CREAM,    15

# --- how many of what ------------------------------------------------------
        .equ N_STARS,    40          # the stars that fall down
        .equ N_SSTARS,   80          # the stars that sit still
        .equ N_ENEMY,    4
        .equ N_BULLET,   4
        .equ N_BOOM,     4
        .equ SHIP_W,     10
        .equ SHIP_H,     8
        .equ ALIEN_W,    8
        .equ ALIEN_H,    6
        .equ FONT_W,     6
        .equ FONT_H,     8
        .equ BOOM_LIFE,  10          # how long an explosion lasts

# ===========================================================================
#  the program
# ===========================================================================
_start:
        cld
        call flush_keys
        mov al, 0x13
        call set_video_mode
        call hide_mouse
        call init_palette
        call init_state
        call play_intro

# --- the title screen ------------------------------------------------------
        call draw_title
title_loop:
        mov bx, 20
        call wait_ms
        call poll_key
        cmp byte ptr [key_flag], 0
        je title_loop
        cmp byte ptr [key_ascii], 0x1b
        je quit
        call start_game

# --- the game --------------------------------------------------------------
game_loop:
        call poll_key
        call handle_input
        cmp byte ptr [game_over], 0
        jne game_over_loop
        call update_stars
        call update_bullets
        call update_enemies
        call check_hits
        call update_booms
        call draw_player
        call draw_hud
        inc byte ptr [frame]
        mov bx, 20
        call wait_ms
        jmp game_loop

# --- the game over screen --------------------------------------------------
game_over_loop:
        call draw_game_over
game_over_wait:
        mov bx, 20
        call wait_ms
        call poll_key
        cmp byte ptr [key_flag], 0
        je game_over_wait
        cmp byte ptr [key_ascii], 0x1b
        je quit
        cmp byte ptr [key_ascii], ' '
        jne game_over_wait
        # space starts a new game, and the game goes on from there
        call start_game
        jmp game_loop

# --- back to DOS -----------------------------------------------------------
quit:
        mov al, 0x03
        call set_video_mode
        mov ax, 0x4c00
        int 0x21

# ===========================================================================
#  the palette
# ===========================================================================
#
#  index, red, green, blue
palette:
        .byte 0,   0,  0,  0        # black
        .byte 1,   0,  0, 10        # the night sky
        .byte 2,   0,  0, 20
        .byte 3,   0,  0, 30
        .byte 4,   0,  0, 40
        .byte 5,   0, 63,  0        # green
        .byte 6,  30, 63,  0        # light green
        .byte 7,  63, 63,  0        # yellow
        .byte 8,  63, 40,  0        # orange
        .byte 9,  63, 10,  0        # red
        .byte 10, 63, 30,  0        # pink
        .byte 11, 63,  0, 40        # magenta
        .byte 12, 63, 63, 63        # white
        .byte 13, 24, 24, 24        # dark grey
        .byte 14, 44, 44, 44        # grey
        .byte 15, 63, 63, 55        # cream

# init_palette: push the sixteen colours into the VGA palette
init_palette:
        push ax
        push bx
        push cx
        push dx
        push si
        mov si, offset palette
        mov cx, 16
1:
        mov al, [si]                # the index
        mov ah, [si+1]              # red
        mov bh, [si+2]              # green
        mov bl, [si+3]              # blue
        call set_color
        add si, 4
        loop 1b
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# set_color: AL = the index, AH = red, BH = green, BL = blue
#
# INT 10h AX=1010h sets one colour of the VGA palette: BL is the colour
# number, DH the red, CH the green and CL the blue, all from 0 to 63.
set_color:
        push ax
        push bx
        push cx
        mov cl, bl                  # the blue, before BL is reused
        mov bl, al                  # the colour number
        mov ch, bh                  # the green
        mov dh, ah                  # the red
        xor bh, bh
        mov ax, 0x1010
        int 0x10
        pop cx
        pop bx
        pop ax
        ret

# ===========================================================================
#  the small services
# ===========================================================================

# hide_mouse: take the mouse pointer out of the picture
#
# DOSBox draws the mouse pointer into the screen itself, and this game does
# not use the mouse, so the pointer would sit in the corner of the picture and
# scribble over it.  The BIOS mouse driver can hide it.
hide_mouse:
        push ax
        mov ax, 0x0200              # hide the pointer
        int 0x33
        pop ax
        ret

# set_video_mode: AL = the mode number
set_video_mode:
        mov ah, 0x00
        int 0x10
        ret

# wait_ms: BX = the number of milliseconds to wait
#
# INT 15h/86h waits for a number of microseconds.  DOSBox keeps the count in
# CX:DX and its RTC handler takes 997 off it on every RTC interrupt, which on
# DOSBox happens about 600 times a second, so a wait of a millisecond comes
# out a little longer than asked for.  That does not matter here: the wait is
# real time and not tied to how fast the host is, which is what the game
# needs, and the timings below are tuned for what DOSBox actually does.
wait_ms:
        push ax
        push cx
        push dx
        mov ax, bx
        mov dx, 1000
        mul dx                      # dx:ax = the milliseconds * 1000
        mov cx, dx
        mov dx, ax
        mov ax, 0x8600
        int 0x15
        pop dx
        pop cx
        pop ax
        ret

# tone: AX = the PIT divisor, BX = the number of milliseconds
#
# The divisor is 1193180 / the frequency in hertz, so 2281 is C5.
tone:
        push ax
        push bx
        push cx
        push dx
        mov cx, ax                  # keep the divisor
        mov dx, 0x43
        mov al, 0xb6                # counter 2, low byte then high, mode 3
        out dx, al
        mov dx, 0x42
        mov al, cl
        out dx, al
        mov al, ch
        out dx, al
        mov dx, 0x61
        in al, dx
        or al, 3                    # turn the speaker on
        out dx, al
        call wait_ms
        mov dx, 0x61
        in al, dx
        and al, 0xfc                # and off again
        out dx, al
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# play_melody: SI = a table of (divisor, milliseconds) pairs, zero ended
play_melody:
        push ax
        push bx
        push si
1:
        mov ax, [si]
        test ax, ax
        jz 2f
        mov bx, [si+2]
        call tone
        add si, 4
        jmp 1b
2:
        pop si
        pop bx
        pop ax
        ret

# play_intro: the little tune that plays over the title screen
play_intro:
        push si
        mov si, offset melody
        call play_melody
        pop si
        ret

# flush_keys: throw away the keys that are still in the BIOS keyboard buffer
#
# The game is usually started from a script or from the WhereQ launcher, and
# the key that started it can still be in the buffer.  Without this the title
# screen would be skipped at once and the game would start by itself.
flush_keys:
        push ax
1:
        mov ah, 0x01
        int 0x16
        jz 2f                      # the buffer is empty, we are done
        mov ah, 0x00
        int 0x16                   # take the key out of the buffer
        jmp 1b
2:
        pop ax
        ret

# poll_key: read one key from the BIOS keyboard buffer, if there is one
#
# key_flag is set to 1 when a key was read, key_ascii holds the character
# and key_scan the scan code (the arrow keys have no character, so they
# are told apart by their scan code).
poll_key:
        push ax
        mov ah, 0x01
        int 0x16
        jz 1f
        mov ah, 0x00
        int 0x16
        mov [key_ascii], al
        mov [key_scan], ah
        mov byte ptr [key_flag], 1
        pop ax
        ret
1:
        mov byte ptr [key_flag], 0
        pop ax
        ret

# rand16: a random number in ax, from a plain linear congruential generator
rand16:
        mov ax, [seed]
        mov bx, 25173
        mul bx                      # dx:ax = seed * 25173
        add ax, 13849
        adc dx, 0
        mov [seed], ax
        ret

# ===========================================================================
#  the drawing
# ===========================================================================

# bg_colour: AX = the row, AL = the colour of that row
#
# The sky fades from bright blue at the top to almost black at the bottom.
# The fade is dithered: every other row is one step darker, so the steps do
# not show up as hard bands.  The colour only depends on the row, which is
# what makes erasing a sprite cheap.
bg_colour:
        push bx
        push di
        mov di, ax                  # the row, we need its parity later
        cmp ax, HUD_H
        jb 1f                       # the score bar
        cmp ax, GROUND_Y
        jae 2f                      # the ground
        sub ax, SKY_TOP
        mov dx, 8
        mul dx                      # dx:ax = the distance from the top * 8
        mov bx, GROUND_Y - SKY_TOP
        div bx                      # ax = the step of the fade, 0 to 7
        mov cl, 4
        mov bl, al                  # keep the step
        shr al, 1                   # two rows for every step
        sub cl, al                  # cl = the colour of this row
        test bl, 1
        jz 4f                       # a whole step, no dithering
        dec cl                      # a half step: dither it
        test di, 1                  # the rows take turns
        jnz 5f
        inc cl                      # the even rows get the brighter colour
5:
        cmp cl, 1
        jge 4f
        mov cl, 1
4:
        mov al, cl
        pop di
        pop bx
        ret
1:
        mov al, C_DGREY
        pop di
        pop bx
        ret
2:
        mov al, C_GREY
        pop di
        pop bx
        ret

# fill_rect: BX = x, CX = y, DX = the width, SI = the height, AL = the colour
#
# The rows are filled with rep stosb, which is a lot faster than writing
# the pixels one by one.
fill_rect:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        mov [fr_color], al
        mov [fr_w], dx
        mov ax, 0xa000              # the video memory
        mov es, ax
        mov ax, cx
        mov dx, SCR_W
        mul dx                      # dx:ax = y * 320
        add ax, bx
        mov di, ax
        mov bx, si                  # the rows that are left
1:
        mov cx, [fr_w]
        mov al, [fr_color]
        cld
        rep stosb
        mov ax, SCR_W
        sub ax, [fr_w]
        add di, ax
        dec bx
        jnz 1b
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# fill_row: BX = x, CX = y, DX = the width, AL = the colour
fill_row:
        push ax
        push bx
        push cx
        push dx
        push di
        mov [fr_color], al
        mov [fr_w], dx
        mov ax, 0xa000              # the video memory
        mov es, ax
        mov ax, cx
        mov dx, SCR_W
        mul dx                      # dx:ax = y * 320
        add ax, bx
        mov di, ax
        mov cx, [fr_w]
        mov al, [fr_color]
        cld
        rep stosb
        pop di
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# erase_rect: BX = x, CX = y, DX = the width, SI = the height
#
# The rectangle is filled with the background colour of every row, so it
# disappears into the sky again.
erase_rect:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        mov [er_x], bx
        mov [er_y], cx
        mov [er_w], dx
        mov [er_h], si
1:
        mov ax, [er_y]
        call bg_colour
        mov [er_color], al
        mov bx, [er_x]
        mov cx, [er_y]
        mov dx, [er_w]
        mov si, 1
        mov al, [er_color]
        call fill_rect
        inc word ptr [er_y]
        dec word ptr [er_h]
        jnz 1b
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# plot: AX = x, BX = y, CL = the colour
plot:
        push ax
        push bx
        push cx
        push dx
        push di
        mov di, ax
        mov ax, bx
        mov dx, SCR_W
        mul dx                      # dx:ax = y * 320
        add di, ax
        mov ax, 0xa000
        mov es, ax
        mov al, cl
        stosb
        pop di
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# draw_sprite: BX = x, CX = y, the sprite is in sp_data
#
# A sprite is a list of bytes, one per pixel: 0 leaves the background
# alone, 1 is the first colour and 2 the second.  The size of the sprite
# is in sp_w and sp_h, the colours in sp_c1 and sp_c2.
draw_sprite:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        push bp
        mov si, [sp_data]           # the picture
        mov [sp_x], bx
        mov [sp_y], cx
        mov ax, 0xa000
        mov es, ax
        mov ax, [sp_y]
        mov dx, SCR_W
        mul dx
        mov di, ax
        add di, [sp_x]
        xor bx, bx                  # the row
1:
        xor cx, cx                  # the column
2:
        mov al, [si]
        inc si
        test al, al
        jz 5f
        cmp al, 1
        je 3f
        mov al, [sp_c2]
        jmp 4f
3:
        mov al, [sp_c1]
4:
        mov byte ptr es:[di], al
        inc di
        jmp 6f
5:
        inc di
6:
        inc cx
        cmp cx, [sp_w]
        jb 2b
        mov ax, [sp_w]
        mov dx, SCR_W
        sub dx, ax
        add di, dx
        inc bx
        cmp bx, [sp_h]
        jb 1b
        pop bp
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# draw_text: SI = the text, the position and the colour are in txt_x,
# txt_y, txt_col and txt_scale
draw_text:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        push bp
        mov bp, si
1:
        mov al, [bp]
        test al, al
        jz 2f
        inc bp
        cmp al, 32
        jb 1b
        sub al, 32
        mov ah, 0
        shl ax, 1
        shl ax, 1
        shl ax, 1                   # eight bytes per glyph
        add ax, offset font_data
        mov si, ax
        call draw_glyph
        jmp 1b
2:
        pop bp
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# draw_glyph: SI points at the eight bytes of one glyph
draw_glyph:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        push bp
        xor bx, bx                  # the row of the glyph
1:
        mov al, [si]
        inc si
        mov [g_bits], al            # the bits of this row, the drawing uses AX
        xor di, di                  # the column of the glyph
2:
        mov al, [g_bits]
        test al, 0x80
        jz 3f
        # the block of pixels, txt_scale by txt_scale
        mov ax, di
        mov cx, [txt_scale]
        mul cx
        add ax, [txt_x]
        mov [blk_x], ax
        mov ax, bx
        mov cx, [txt_scale]
        mul cx
        add ax, [txt_y]
        mov [blk_y], ax
        mov al, [txt_col]
        mov [blk_col], al
        mov al, [txt_scale]
        mov [blk_scale], al
        call plot_block
3:
        mov al, [g_bits]
        shl al, 1                   # the font is stored with the left pixel in the top bit
        mov [g_bits], al
        inc di
        cmp di, FONT_W
        jb 2b
        inc bx
        cmp bx, FONT_H
        jb 1b
        # and on to the next character
        mov ax, [txt_scale]
        mov bl, FONT_W
        mul bl
        add [txt_x], ax
        pop bp
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# plot_block: a block of blk_scale by blk_scale pixels at blk_x, blk_y
plot_block:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        mov ax, 0xa000
        mov es, ax
        mov ax, [blk_y]
        mov dx, SCR_W
        mul dx
        add ax, [blk_x]
        mov di, ax
        mov si, [blk_scale]
1:
        mov cx, [blk_scale]
2:
        mov al, [blk_col]
        stosb
        dec cx
        jnz 2b
        mov ax, [blk_scale]
        mov dx, SCR_W
        sub dx, ax
        add di, dx
        dec si
        jnz 1b
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# draw_number: the number in num_value, at num_x, num_y, in num_col
draw_number:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        mov ax, [num_value]
        mov bx, 5                   # the last digit
        mov si, offset num_buf
        add bx, si
        mov cx, 6
1:
        xor dx, dx
        div word ptr [ten]          # dx:ax / 10
        add dx, '0'
        mov [bx], dl
        dec bx
        loop 1b
        mov byte ptr [num_buf+6], 0
        mov si, offset num_buf
        mov ax, [num_x]
        mov [txt_x], ax
        mov ax, [num_y]
        mov [txt_y], ax
        mov al, [num_col]
        mov [txt_col], al
        mov byte ptr [txt_scale], 1
        call draw_text
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# ===========================================================================
#  the state
# ===========================================================================

# init_state: set everything up for a new game
init_state:
        push ax
        push bx
        push cx
        push dx
        push si
        mov word ptr [seed], 0x1234
        mov word ptr [score], 0
        mov word ptr [score_shown], 0xffff
        mov word ptr [hi_score], 0
        mov byte ptr [lives], 3
        mov byte ptr [frame], 0
        mov byte ptr [game_over], 0
        mov byte ptr [spawn_cd], 40
        mov byte ptr [fire_cd], 0
        mov word ptr [player_x], 155
        mov word ptr [player_y], 150

        # the stars that sit still
        mov si, offset sstars
        mov cx, N_SSTARS
1:
        call rand16
        mov bx, SCR_W - 4
        xor dx, dx
        div bx                      # dx = the remainder, 0 to 315
        mov [si], dl
        call rand16
        mov bx, GROUND_Y - SKY_TOP - 4
        xor dx, dx
        div bx
        add dx, SKY_TOP
        mov [si+1], dl
        call rand16
        and al, 3
        cmp al, 2
        je 2f
        mov byte ptr [si+2], C_SKY3
        jmp 3f
2:
        mov byte ptr [si+2], C_WHITE
3:
        add si, 3
        loop 1b

        # the stars that fall down
        mov si, offset stars
        mov cx, N_STARS
4:
        call rand16
        mov bx, SCR_W - 4
        xor dx, dx
        div bx
        mov [si], dl
        call rand16
        mov bx, GROUND_Y - SKY_TOP - 4
        xor dx, dx
        div bx
        add dx, SKY_TOP
        mov [si+1], dl
        call rand16
        and al, 3
        jz 5f
        mov byte ptr [si+2], 2
        jmp 6f
5:
        mov byte ptr [si+2], 1
6:
        call rand16
        and al, 3
        cmp al, 3
        je 7f
        mov byte ptr [si+3], C_SKY3
        jmp 8f
7:
        mov byte ptr [si+3], C_WHITE
8:
        add si, 4
        loop 4b

        # the aliens and the bullets start out empty
        mov si, offset enemies
        mov cx, N_ENEMY
9:
        mov byte ptr [si+5], 0
        add si, 7
        loop 9b
        mov si, offset bullets
        mov cx, N_BULLET
10:
        mov byte ptr [si+4], 0
        add si, 5
        loop 10b
        mov si, offset booms
        mov cx, N_BOOM
11:
        mov byte ptr [si+4], 0
        add si, 5
        loop 11b
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# start_game: a new game, from the title screen or after a game over
start_game:
        push ax
        push cx
        push si
        mov word ptr [score], 0
        mov word ptr [score_shown], 0xffff
        mov byte ptr [lives], 3
        mov byte ptr [game_over], 0
        mov byte ptr [spawn_cd], 30
        mov word ptr [player_x], 155
        mov word ptr [player_y], 150
        mov si, offset enemies
        mov cx, N_ENEMY
1:
        mov byte ptr [si+5], 0
        add si, 7
        loop 1b
        mov si, offset bullets
        mov cx, N_BULLET
2:
        mov byte ptr [si+4], 0
        add si, 5
        loop 2b
        # and the explosions of the game that just ended
        mov si, offset booms
        mov cx, N_BOOM
12:
        mov byte ptr [si+4], 0
        add si, 5
        loop 12b
        # and the whole picture again, the game over screen was over it
        call draw_background
        call draw_sstars
        call draw_stars
        call draw_hud
        pop si
        pop cx
        pop ax
        ret

# ===========================================================================
#  the input
# ===========================================================================

# handle_input: move the ship, fire, or quit
handle_input:
        push ax
        cmp byte ptr [key_flag], 0
        je 10f
        mov byte ptr [key_flag], 0
        mov al, [key_ascii]
        mov ah, [key_scan]

        cmp al, 0x1b                # escape
        je 11f
        cmp al, ' '                 # space fires
        je 9f

        # the arrow keys have no character, only a scan code
        cmp ah, 0x48                # up
        je 1f
        cmp ah, 0x50                # down
        je 2f
        cmp ah, 0x4b                # left
        je 3f
        cmp ah, 0x4d                # right
        je 4f

        # and the same keys on a phone keyboard
        cmp al, 'w'
        je 1f
        cmp al, 'W'
        je 1f
        cmp al, 's'
        je 2f
        cmp al, 'S'
        je 2f
        cmp al, 'a'
        je 3f
        cmp al, 'A'
        je 3f
        cmp al, 'd'
        je 4f
        cmp al, 'D'
        je 4f
        jmp 10f

1:
        sub word ptr [player_y], 3
        jmp 5f
2:
        add word ptr [player_y], 3
        jmp 5f
3:
        sub word ptr [player_x], 4
        jmp 5f
4:
        add word ptr [player_x], 4
5:
        cmp word ptr [player_x], 4
        jge 6f
        mov word ptr [player_x], 4
6:
        cmp word ptr [player_x], SCR_W - SHIP_W - 4
        jle 7f
        mov word ptr [player_x], SCR_W - SHIP_W - 4
7:
        cmp word ptr [player_y], SKY_TOP + 6
        jge 8f
        mov word ptr [player_y], SKY_TOP + 6
8:
        cmp word ptr [player_y], GROUND_Y - SHIP_H - 2
        jle 10f
        mov word ptr [player_y], GROUND_Y - SHIP_H - 2
        jmp 10f
9:
        call fire_bullet
10:
        pop ax
        ret
11:
        pop ax
        jmp quit

# fire_bullet: send a shot up the screen, if the ship is ready
fire_bullet:
        push ax
        push bx
        push cx
        push si
        cmp byte ptr [fire_cd], 0
        jne 3f
        mov byte ptr [fire_cd], 6
        mov si, offset bullets
        mov cx, N_BULLET
1:
        cmp byte ptr [si+4], 0
        jne 2f
        mov ax, [player_x]
        add ax, 4
        mov [si], ax
        mov ax, [player_y]
        sub ax, 7
        mov [si+2], ax
        mov byte ptr [si+4], 1
        mov ax, 905                # 1318 hertz
        mov bx, 30
        call tone
        jmp 3f
2:
        add si, 5
        loop 1b
3:
        pop si
        pop cx
        pop bx
        pop ax
        ret

# ===========================================================================
#  the game logic
# ===========================================================================

# update_stars: the stars fall down the screen
update_stars:
        push ax
        push bx
        push cx
        push si
        mov si, offset stars
        mov cx, N_STARS
1:
        # erase the star where it is now, with the colour of the sky there
        push cx                     # cx is the loop counter, so it is saved first
        mov al, [si+1]              # the row
        xor ah, ah
        call bg_colour              # al = the colour of that row
        mov cl, al
        mov al, [si]                # the column
        xor ah, ah
        mov bl, [si+1]              # the row
        xor bh, bh
        call plot
        pop cx

        # and move it down
        mov al, [si+1]
        add al, [si+2]
        cmp al, GROUND_Y
        jb 2f
        mov al, SKY_TOP             # a new star at the top
        mov [si+1], al
        call rand16
        mov bx, SCR_W - 4
        xor dx, dx
        div bx
        mov [si], dl
        jmp 3f
2:
        mov [si+1], al
3:
        # and draw it where it is now
        mov al, [si]                # the column
        xor ah, ah
        mov bl, [si+1]              # the row
        xor bh, bh
        push cx                     # cx is the loop counter, so it is saved first
        mov cl, [si+3]
        call plot
        pop cx
        add si, 4
        loop 1b
        pop si
        pop cx
        pop bx
        pop ax
        ret

# update_bullets: the shots fly up, and leave at the top of the screen
update_bullets:
        push ax
        push bx
        push cx
        push si
        cmp byte ptr [fire_cd], 0
        je 1f
        dec byte ptr [fire_cd]
1:
        mov si, offset bullets
        mov cx, N_BULLET
2:
        cmp byte ptr [si+4], 0
        je 4f
        # erase the shot where it is now, with the colour of the sky there
        push cx                     # cx is the loop counter, so it is saved first
        mov al, [si+2]              # the row
        xor ah, ah
        call bg_colour              # al = the colour of that row
        mov cl, al
        mov al, [si]                # the column
        xor ah, ah
        mov bl, [si+2]              # the row
        xor bh, bh
        call plot
        pop cx
        # and move it up
        mov ax, [si+2]
        sub ax, 6
        cmp ax, SKY_TOP
        jae 3f
        mov byte ptr [si+4], 0
        jmp 4f
3:
        mov [si+2], ax
        # and draw it where it is now
        mov al, [si]                # the column
        xor ah, ah
        mov bl, [si+2]              # the row
        xor bh, bh
        push cx                     # cx is the loop counter, so it is saved first
        mov cl, C_YELLOW
        call plot
        pop cx
4:
        add si, 5
        loop 2b
        pop si
        pop cx
        pop bx
        pop ax
        ret

# update_enemies: the aliens come down, and the ship has to shoot them
update_enemies:
        push ax
        push bx
        push cx
        push dx
        push si
        dec byte ptr [spawn_cd]
        jnz 1f
        call rand16
        and al, 31
        add al, 24
        mov [spawn_cd], al
        call spawn_enemy
1:
        mov si, offset enemies
        mov cx, N_ENEMY
2:
        cmp byte ptr [si+5], 0
        je 5f
        call move_enemy
5:
        add si, 7
        loop 2b
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# move_enemy: SI = the alien, which is erased, moved and drawn again
move_enemy:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        mov di, si                  # the alien is still needed

        # erase it where it is now
        mov ax, [di]
        mov bx, ax
        mov ax, [di+2]
        mov cx, ax
        push si
        mov dx, ALIEN_W
        mov si, ALIEN_H
        call erase_rect
        pop si

        # and move it down and to the side
        mov ax, [di+2]
        add ax, 1
        cmp ax, GROUND_Y - ALIEN_H
        jb 1f
        mov byte ptr [di+5], 0      # it got past the ship
        call lose_life
        jmp 3f
1:
        mov [di+2], ax
        mov ax, [di]
        add al, [di+6]
        adc ah, 0
        mov [di], ax
        cmp ax, 6
        jge 2f
        mov byte ptr [di+6], 1
2:
        cmp ax, SCR_W - ALIEN_W - 6
        jle 3f
        mov byte ptr [di+6], 0xff
3:
        # and draw it where it is now
        mov ax, [di+4]
        cmp al, 0
        je 4f
        mov ax, offset alien_b
        jmp 5f
4:
        mov ax, offset alien_a
5:
        mov [sp_data], ax
        mov bx, [di]
        mov cx, [di+2]
        mov byte ptr [sp_c1], C_RED
        mov byte ptr [sp_c2], C_PINK
        mov word ptr [sp_w], ALIEN_W
        mov word ptr [sp_h], ALIEN_H
        call draw_sprite
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# spawn_enemy: put a new alien in the first free slot
spawn_enemy:
        push ax
        push cx
        push si
        mov si, offset enemies
        mov cx, N_ENEMY
1:
        cmp byte ptr [si+5], 0
        je 2f
        add si, 7
        loop 1b
        jmp 3f
2:
        call rand16
        and al, 127
        add al, 20
        mov [si], al
        mov word ptr [si+2], SKY_TOP
        call rand16
        and al, 1
        mov [si+4], al              # which picture it is
        mov byte ptr [si+5], 1      # it is alive
        mov byte ptr [si+6], 1      # and it drifts to the right
3:
        pop si
        pop cx
        pop ax
        ret

# check_hits: does a shot hit an alien?
check_hits:
        push ax
        push bx
        push cx
        push dx
        push si
        push di
        mov si, offset bullets
        mov cx, N_BULLET
1:
        cmp byte ptr [si+4], 0
        je 3f
        mov bx, [si]                # the shot
        mov dx, [si+2]
        mov di, offset enemies
        mov ax, N_ENEMY
        push cx                     # cx is the bullet counter, so it is saved here
2:
        cmp byte ptr [di+5], 0
        je 4f
        xor cx, cx
        mov cl, [di]                # the column of the alien
        cmp bx, cx
        jb 4f
        add cx, ALIEN_W
        cmp bx, cx
        jae 4f
        mov cx, [di+2]
        cmp dx, cx
        jb 4f
        add cx, ALIEN_H
        cmp dx, cx
        jae 4f
        # a hit: the alien is gone and the score goes up
        mov byte ptr [di+5], 0
        mov byte ptr [si+4], 0
        mov ax, [score]
        add ax, 10
        mov [score], ax
        mov bx, [hi_score]
        cmp ax, bx
        jle 5f
        mov [hi_score], ax
5:
        xor ax, ax
        mov al, [di]                # the column of the alien
        mov [boom_x], ax
        mov ax, [di+2]
        mov [boom_y], ax
        call spawn_boom
        pop cx                      # the bullet counter is back
        jmp 3f
4:
        add di, 7
        dec ax
        jnz 2b
        pop cx                      # the bullet counter is back
3:
        add si, 5
        loop 1b
        pop di
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# spawn_boom: an explosion where the alien was
spawn_boom:
        push ax
        push bx
        push cx
        push si
        mov si, offset booms
        mov cx, N_BOOM
1:
        cmp byte ptr [si+4], 0
        jne 2f
        mov ax, [boom_x]
        mov [si], ax
        mov ax, [boom_y]
        mov [si+2], ax
        mov byte ptr [si+4], BOOM_LIFE
        jmp 3f
2:
        add si, 5
        loop 1b
3:
        pop si
        pop cx
        pop bx
        pop ax
        ret

# update_booms: the explosions grow and shrink
update_booms:
        push ax
        push bx
        push cx
        push si
        mov si, offset booms
        mov cx, N_BOOM
1:
        cmp byte ptr [si+4], 0
        je 2f
        push cx                     # cx is the loop counter, so it is saved here
        # erase the explosion where it is now
        mov ax, [si]
        mov bx, ax
        mov ax, [si+2]
        mov cx, ax
        push si                     # the explosion is still needed
        mov dx, 9
        mov si, 9
        call erase_rect
        pop si
        dec byte ptr [si+4]
        jz 3f
        # and draw it again, a bit smaller
        mov al, [si+4]
        shr al, 2
        inc al
        mov [boom_r], al
        mov bx, [si]
        add bx, 4
        sub bx, [boom_r]
        mov cx, [si+2]
        add cx, 4
        sub cx, [boom_r]
        mov dx, [boom_r]
        shl dx, 1
        inc dx
        push si                     # the explosion is still needed
        mov si, dx                  # the height of the square
        mov al, C_ORANGE
        call fill_rect
        pop si
3:
        pop cx                      # the loop counter is back
2:
        add si, 5
        loop 1b
        pop si
        pop cx
        pop bx
        pop ax
        ret

# lose_life: an alien got past the ship
lose_life:
        push ax
        cmp byte ptr [lives], 0
        je 2f
        dec byte ptr [lives]
        cmp byte ptr [lives], 0
        jne 1f
        mov byte ptr [game_over], 1
        jmp 2f
1:
        mov ax, 1200
        mov bx, 120
        call tone
2:
        pop ax
        ret

# ===========================================================================
#  the picture
# ===========================================================================

# draw_title: the title screen
draw_title:
        call draw_background
        call draw_sstars
        call draw_stars

        mov si, offset str_whereq
        mov word ptr [txt_x], 106
        mov word ptr [txt_y], 30
        mov byte ptr [txt_col], C_WHITE
        mov byte ptr [txt_scale], 3
        call draw_text

        mov si, offset str_starfall
        mov word ptr [txt_x], 112
        mov word ptr [txt_y], 62
        mov byte ptr [txt_col], C_YELLOW
        mov byte ptr [txt_scale], 2
        call draw_text

        mov si, offset str_sub
        mov word ptr [txt_x], 73
        mov word ptr [txt_y], 94
        mov byte ptr [txt_col], C_SKY4
        mov byte ptr [txt_scale], 1
        call draw_text

        mov si, offset str_press
        mov word ptr [txt_x], 94
        mov word ptr [txt_y], 124
        mov byte ptr [txt_col], C_CREAM
        mov byte ptr [txt_scale], 1
        call draw_text

        mov si, offset str_keys
        mov word ptr [txt_x], 12
        mov word ptr [txt_y], 184
        mov byte ptr [txt_col], C_DGREY
        mov byte ptr [txt_scale], 1
        call draw_text
        ret

# draw_game_over: the screen that says the game is over
draw_game_over:
        call draw_background
        call draw_sstars

        mov si, offset str_over
        mov word ptr [txt_x], 88
        mov word ptr [txt_y], 60
        mov byte ptr [txt_col], C_RED
        mov byte ptr [txt_scale], 3
        call draw_text

        mov si, offset str_scored
        mov word ptr [txt_x], 118
        mov word ptr [txt_y], 110
        mov byte ptr [txt_col], C_WHITE
        mov byte ptr [txt_scale], 1
        call draw_text

        mov ax, [score]
        mov [num_value], ax
        mov word ptr [num_x], 145
        mov word ptr [num_y], 124
        mov byte ptr [num_col], C_YELLOW
        call draw_number

        mov si, offset str_restart
        mov word ptr [txt_x], 82
        mov word ptr [txt_y], 140
        mov byte ptr [txt_col], C_LGREEN
        mov byte ptr [txt_scale], 1
        call draw_text

        mov si, offset str_keys
        mov word ptr [txt_x], 12
        mov word ptr [txt_y], 184
        mov byte ptr [txt_col], C_DGREY
        mov byte ptr [txt_scale], 1
        call draw_text
        ret

# draw_background: the sky, the score bar and the ground
draw_background:
        push ax
        push bx
        push cx
        push dx
        push si
        # the score bar
        mov bx, 0
        mov cx, 0
        mov dx, SCR_W
        mov si, HUD_H
        mov al, C_DGREY
        call fill_rect
        # the ground
        mov bx, 0
        mov cx, GROUND_Y
        mov dx, SCR_W
        mov si, SCR_H - GROUND_Y
        mov al, C_GREY
        call fill_rect
        # and the sky, one row of colour at a time
        mov cx, SKY_TOP
1:
        cmp cx, GROUND_Y
        jae 2f
        push cx
        mov ax, cx
        call bg_colour
        pop cx
        mov bx, 0
        mov dx, SCR_W
        call fill_row
        inc cx
        jmp 1b
2:
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# draw_sstars: the stars that sit still
draw_sstars:
        push ax
        push bx
        push cx
        push si
        mov si, offset sstars
        mov cx, N_SSTARS
1:
        mov al, [si]                # the column
        xor ah, ah
        mov bl, [si+1]              # the row
        xor bh, bh
        push cx                     # cx is the loop counter, so it is saved first
        mov cl, [si+2]
        call plot
        pop cx
        add si, 3
        loop 1b
        pop si
        pop cx
        pop bx
        pop ax
        ret

# draw_stars: the stars that fall, all of them
draw_stars:
        push ax
        push bx
        push cx
        push si
        mov si, offset stars
        mov cx, N_STARS
1:
        mov al, [si]                # the column
        xor ah, ah
        mov bl, [si+1]              # the row
        xor bh, bh
        push cx                     # cx is the loop counter, so it is saved first
        mov cl, [si+3]
        call plot
        pop cx
        add si, 4
        loop 1b
        pop si
        pop cx
        pop bx
        pop ax
        ret

# draw_player: the ship, moved to where the player put it
draw_player:
        push ax
        push bx
        push cx
        push dx
        push si
        # erase the ship where it was
        mov ax, [ship_x]
        mov bx, ax
        mov ax, [ship_y]
        mov cx, ax
        push si
        mov dx, SHIP_W
        mov si, SHIP_H
        call erase_rect
        pop si
        # and draw it where it is now
        mov ax, [player_x]
        mov [ship_x], ax
        mov ax, [player_y]
        mov [ship_y], ax
        mov bx, [ship_x]
        mov cx, [ship_y]
        mov ax, offset ship
        mov [sp_data], ax
        mov byte ptr [sp_c1], C_WHITE
        mov byte ptr [sp_c2], C_LGREEN
        mov word ptr [sp_w], SHIP_W
        mov word ptr [sp_h], SHIP_H
        call draw_sprite
        pop si
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# draw_hud: the score bar, but only when something changed
draw_hud:
        push ax
        push bx
        push cx
        push dx
        mov ax, [score]
        cmp ax, [score_shown]
        je 2f
        mov [score_shown], ax
        # the score
        mov si, offset str_score
        mov word ptr [txt_x], 4
        mov word ptr [txt_y], 3
        mov byte ptr [txt_col], C_WHITE
        mov byte ptr [txt_scale], 1
        call draw_text
        mov ax, [score]
        mov [num_value], ax
        mov word ptr [num_x], 40
        mov word ptr [num_y], 3
        mov byte ptr [num_col], C_YELLOW
        call draw_number
        # the best score
        mov si, offset str_best
        mov word ptr [txt_x], 110
        mov word ptr [txt_y], 3
        mov byte ptr [txt_col], C_WHITE
        mov byte ptr [txt_scale], 1
        call draw_text
        mov ax, [hi_score]
        mov [num_value], ax
        mov word ptr [num_x], 140
        mov word ptr [num_y], 3
        mov byte ptr [num_col], C_YELLOW
        call draw_number
        # and the ships that are left
        mov si, offset str_ships
        mov word ptr [txt_x], 210
        mov word ptr [txt_y], 3
        mov byte ptr [txt_col], C_WHITE
        mov byte ptr [txt_scale], 1
        call draw_text
        # the ships that are left, as a single digit
        mov al, [lives]
        add al, '0'
        mov [life_buf], al
        mov byte ptr [life_buf+1], 0
        mov si, offset life_buf
        mov word ptr [txt_x], 250
        mov word ptr [txt_y], 3
        mov byte ptr [txt_col], C_LGREEN
        mov byte ptr [txt_scale], 1
        call draw_text
2:
        pop dx
        pop cx
        pop bx
        pop ax
        ret

# ===========================================================================
#  the pictures
# ===========================================================================

# the ship: 10 by 8
ship:
        .byte 0, 0, 0, 0, 1, 1, 0, 0, 0, 0
        .byte 0, 0, 0, 1, 2, 2, 1, 0, 0, 0
        .byte 0, 0, 1, 2, 2, 2, 2, 1, 0, 0
        .byte 0, 1, 2, 2, 2, 2, 2, 2, 1, 0
        .byte 1, 2, 2, 2, 2, 2, 2, 2, 2, 1
        .byte 2, 2, 2, 2, 2, 2, 2, 2, 2, 2
        .byte 2, 1, 1, 1, 1, 1, 1, 1, 1, 2
        .byte 0, 0, 0, 1, 0, 0, 0, 1, 0, 0

# the first alien: 8 by 6
alien_a:
        .byte 0, 1, 1, 0, 0, 1, 1, 0
        .byte 1, 2, 2, 1, 1, 2, 2, 1
        .byte 1, 2, 2, 2, 2, 2, 2, 1
        .byte 1, 1, 2, 2, 2, 2, 1, 1
        .byte 0, 1, 1, 2, 2, 1, 1, 0
        .byte 0, 0, 1, 1, 1, 1, 0, 0

# the second alien: 8 by 6
alien_b:
        .byte 0, 0, 1, 1, 1, 1, 0, 0
        .byte 0, 1, 2, 2, 2, 2, 1, 0
        .byte 1, 2, 1, 1, 1, 1, 2, 1
        .byte 1, 2, 2, 2, 2, 2, 2, 1
        .byte 0, 1, 1, 1, 1, 1, 1, 0
        .byte 0, 0, 1, 0, 0, 1, 0, 0

# ===========================================================================
#  the words
# ===========================================================================
str_whereq:     .asciz "WHEREQ"
str_starfall:   .asciz "STARFALL"
str_sub:        .asciz "A TINY DOS GAME FOR YOUR PHONE"
str_press:      .asciz "PRESS ANY KEY TO START"
str_keys:       .asciz "ARROWS OR WASD MOVE   SPACE FIRE   ESC QUIT"
str_score:      .asciz "SCORE"
str_best:       .asciz "BEST"
str_ships:      .asciz "SHIPS"
str_over:       .asciz "GAME OVER"
str_scored:     .asciz "YOU SCORED"
str_restart:    .asciz "PRESS SPACE TO PLAY AGAIN"

# ===========================================================================
#  the variables
# ===========================================================================
player_x:       .word 155
player_y:       .word 150
ship_x:         .word 155
ship_y:         .word 150
score:          .word 0
score_shown:    .word 0xffff
hi_score:       .word 0
lives:          .byte 3
frame:          .byte 0
game_over:      .byte 0
spawn_cd:       .byte 40
fire_cd:        .byte 0
seed:           .word 0x1234
key_ascii:      .byte 0
key_scan:       .byte 0
key_flag:       .byte 0

# the text drawing
#
# A note on the sizes here: a variable that is read with a word instruction
# has to be a word, because a word read of a byte variable also reads the
# byte that follows it.  txt_scale, blk_scale and g_bits are therefore
# words, even though they only ever hold a small number.
txt_x:          .word 0
txt_y:          .word 0
txt_col:        .byte 0
txt_scale:      .word 1

# the sprite drawing
sp_x:           .word 0
sp_y:           .word 0
sp_data:        .word 0
sp_w:           .word 0
sp_h:           .word 0
sp_c1:          .byte 0
sp_c2:          .byte 0

# the block drawing
blk_x:          .word 0
blk_y:          .word 0
blk_col:        .byte 0
blk_scale:      .word 1
g_bits:         .word 0

# the numbers
num_value:      .word 0
num_x:          .word 0
num_y:          .word 0
num_col:        .byte 0
num_buf:        .byte 0, 0, 0, 0, 0, 0, 0
life_buf:       .byte 0, 0
ten:            .word 10

# the rectangles
er_x:           .word 0
er_y:           .word 0
er_w:           .word 0
er_h:           .word 0
er_color:       .byte 0
fr_color:       .byte 0
fr_w:           .word 0
boom_x:         .word 0
boom_y:         .word 0
boom_r:         .word 0

# the stars that fall: x, y, speed, colour
stars:          .byte 0, 0, 0, 0
                .zero N_STARS * 4 - 4

# the stars that sit still: x, y, colour
sstars:         .byte 0, 0, 0
                .zero N_SSTARS * 3 - 3

# the aliens: x, y, kind, alive, direction
enemies:        .byte 0, 0, 0, 0, 0, 0, 0
                .zero N_ENEMY * 7 - 7

# the shots: x, y, flying
bullets:        .byte 0, 0, 0, 0, 0
                .zero N_BULLET * 5 - 5

# the explosions: x, y, life
booms:          .byte 0, 0, 0, 0, 0
                .zero N_BOOM * 5 - 5

# the tune: divisor, milliseconds
melody:         .word 2281, 50
                .word 1810, 50
                .word 1522, 50
                .word 1140, 75
                .word 1522, 50
                .word 1810, 50
                .word 2281, 100
                .word 0, 0

        .include "font8x8.inc"
