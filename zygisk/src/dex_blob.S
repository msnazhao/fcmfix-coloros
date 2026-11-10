// The hook dex, linked into the library so a process never has to read the module directory.

    .section .rodata.fcmfix_dex, "a", %progbits
    .balign 16
    .global fcmfix_dex_start
    .hidden fcmfix_dex_start
fcmfix_dex_start:
    .incbin FCMFIX_DEX_PATH
    .global fcmfix_dex_end
    .hidden fcmfix_dex_end
fcmfix_dex_end:
