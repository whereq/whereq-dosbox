; bench.asm - DOSBox CPU-core throughput probe (16-bit DOS .COM)
;
; Measures how many emulated x86 instructions the selected DOSBox CPU core
; executes per *emulated* second, using the BIOS tick counter (INT 1Ah AH=00).
;
; Why BIOS ticks: in DOSBox the PIT/BIOS clock is driven by emulated time
; (PIC_FullIndex() = emulated milliseconds, see src/hardware/timer.cpp), not by
; the host clock. So "instructions per BIOS tick" == "instructions the core
; can execute per emulated second" == exactly the quantity that decides
; whether DOSBox can sustain real-time DOS execution.
;
; DOSBox's default budget is CPU_CycleMax = 3000 cycles/ms
; (src/cpu/cpu.cpp:53) == 3,000,000 instructions per emulated second.
; Anything below that number means the core cannot keep a DOS program
; running at real-time speed.
;
; Build: nasm -f bin bench.asm -o bench.com
; Run:   dosbox -c "c:\bench.com" -c "exit"

INNER   equ 2000        ; inner-loop iterations per outer iteration
OUTER   equ 20000       ; outer iterations
; Total instructions ~= OUTER * (INNER*6 + 3)

        org 0x100

start:
        mov     ax, 0x0000
        int     0x1a                  ; AH=00 -> CX:DX = BIOS tick count (18.2 Hz)
        mov     [tick0_lo], dx
        mov     [tick0_hi], cx

        mov     word [outer_left], OUTER

outer_loop:
        mov     si, INNER
inner_loop:
        add     ax, 0x0139             ; 1
        rol     bx, 1                  ; 2
        xor     cx, 0x5a5a             ; 3
        add     dx, bx                 ; 4
        dec     si                     ; 5
        jnz     inner_loop             ; 6
        dec     word [outer_left]      ; 7
        jnz     outer_loop

        mov     ax, 0x0000
        int     0x1a
        mov     [tick1_lo], dx
        mov     [tick1_hi], cx

        ; elapsed = tick1 - tick0 (32-bit)
        mov     ax, [tick1_lo]
        sub     ax, [tick0_lo]
        mov     [elapsed_lo], ax
        mov     ax, [tick1_hi]
        sbb     ax, [tick0_hi]
        mov     [elapsed_hi], ax

        ; ---- build output line ----
        mov     word [outlen], 0
        mov     word [outpos], 0
        mov     di, outbuf
        mov     [outpos], di

        mov     si, msg_ticks
        call    put_str
        mov     eax, [elapsed_hi]
        mov     ebx, eax
        mov     eax, [elapsed_lo]
        shl     ebx, 16
        or      ebx, eax
        mov     eax, ebx
        call    put_hex32
        mov     si, msg_spc
        call    put_str
        mov     eax, OUTER
        call    put_hex32
        mov     si, msg_crlf
        call    put_str
        mov     bx, [outlen]
        mov     byte [outbuf+bx], '$'   ; AH=09 needs a '$' terminator

        ; ---- write result to C:\BENCH.TXT (host-readable) ----
        ; NOTE: DOSBox 0.74 does not switch the current drive on "mount c",
        ; so the file name MUST carry an explicit drive letter.
        mov     dx, msg_filename
        mov     cx, 0
        mov     ah, 0x3c                ; create/truncate file
        int     0x21
        jc      file_done
        mov     bx, ax
        mov     dx, outbuf
        mov     cx, [outlen]
        mov     ah, 0x40                ; write
        int     0x21
        mov     ah, 0x3e                ; close
        int     0x21
file_done:

        ; ---- echo to console ----
        mov     dx, outbuf
        mov     ah, 0x09
        int     0x21

        mov     ax, 0x4c00
        int     0x21

; ---------------------------------------------------------------------------
; put_str: SI = '$'-terminated string, appended to outbuf
; ---------------------------------------------------------------------------
put_str:
        push    ax
        push    di
        mov     di, [outpos]
ps_loop:
        lodsb
        cmp     al, 0
        je      ps_done
        mov     [di], al
        inc     di
        inc     word [outlen]
        jmp     ps_loop
ps_done:
        mov     [outpos], di
        pop     di
        pop     ax
        ret

; ---------------------------------------------------------------------------
; put_hex32: EAX = 32-bit value, appends 8 hex digits
; ---------------------------------------------------------------------------
put_hex32:
        push    eax
        push    ebx
        push    ecx
        mov     ebx, eax
        mov     ecx, 8
ph_loop:
        mov     eax, ebx
        shr     eax, 28
        and     al, 0x0f
        call    put_hex_digit
        shl     ebx, 4
        dec     ecx
        jnz     ph_loop
        pop     ecx
        pop     ebx
        pop     eax
        ret

; ---------------------------------------------------------------------------
; put_hex_digit: AL = digit 0..15
; ---------------------------------------------------------------------------
put_hex_digit:
        push    ax
        push    bx
        push    di
        mov     di, [outpos]
        add     al, '0'
        cmp     al, '9'
        jbe     phd_ok
        add     al, 7
phd_ok:
        mov     [di], al
        inc     di
        mov     [outpos], di
        inc     word [outlen]
        pop     di
        pop     bx
        pop     ax
        ret

; ---------------------------------------------------------------------------
        msg_ticks:  db "TICKS=", 0
        msg_spc:    db " ", 0
        msg_crlf:   db 13, 10, 0
        msg_filename: db "C:\BENCH.TXT", 0

        tick0_lo:  dw 0
        tick0_hi:  dw 0
        tick1_lo:  dw 0
        tick1_hi:  dw 0
        elapsed_lo: dw 0
        elapsed_hi: dw 0
        outer_left: dw 0
        outlen:    dw 0
        outpos:    dw 0
        outbuf:    db 64 dup(0)

