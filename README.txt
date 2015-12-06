Java x86 PC emulator is a fork of [Retro: a PC/XT emulator](http://www.xs4all.nl/~rjoris/retro/)

Features
--------

Retro is far from a polished product.  Simple DOS programs work on it,
but many things do not work at all.

Implemented:
 * 8086/8088 CPU
 * Color Graphics Adapter (CGA)
 * I8254 timer
 * I8259 interrupt controller
 * I8237 DMA controller
 * keyboard controller
 * floppy drive(s)
 * custom BIOS code (minimal, but sufficient for booting DOS etc)

Still missing:
 * harddisk
 * speaker
 * serial port
 * parallel port

Note that Retro emulates an IBM PC/XT, not a modern PC.  It will never
be possible to run modern (32-bit) operating systems on Retro.


Usage
-----

You can start Retro directly from the JAR file:

  java -jar retro.jar --floppyaimg disk.img

where disk.img is a floppy disk image file exactly 320k, 360k, 720k, 1200k,
1440k or 2880k in size.  The disk image is opened read-only by default.
To override this, add '--floppyareadonly false' to the command line.

Configuration settings can be passed on the command line, as in the above
examples, or through a configuration file.  The sample file retro.cfg
gives an overview of the configurable settings.

For best results, run the emulator on Linux with the Sun Java JRE 1.4.2
or later.  Earlier JRE versions and non-Sun Java environments are in general
too buggy; in particular there are many problems with keyboard events
under X.

The emulator does run on Windows and MacOS, but the screen may look ugly
because even the monospaced font tends to be variable width on these systems.


Compiling
---------

Run the makefile to compile all code:

  make

Or build a JAR file:

  make retro.jar

The makefile uses Jikes to compile Java code, but this can easily be
changed. NASM is needed to assemble the BIOS code.
