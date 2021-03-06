/*
 *  Cpu.java
 *  Joris van Rantwijk
 */

package jx86pc.v1;

/**
 * Emulation of a 8086/8088 CPU.
 */
public class Cpu {

    public static final int regAX = 0;
    public static final int regCX = 1;
    public static final int regDX = 2;
    public static final int regBX = 3;
    public static final int regSP = 4;
    public static final int regBP = 5;
    public static final int regSI = 6;
    public static final int regDI = 7;

    public static final int sregES = 0;
    public static final int sregCS = 1;
    public static final int sregSS = 2;
    public static final int sregDS = 3;

    public static final int flANDMASK = 0xffd7;
    public static final int flORMASK  = 0xf002;
    public static final int flCF = 0x0001;
    public static final int flPF = 0x0004;
    public static final int flAF = 0x0010;
    public static final int flZF = 0x0040;
    public static final int flSF = 0x0080;
    public static final int flTF = 0x0100;
    public static final int flIF = 0x0200;
    public static final int flDF = 0x0400;
    public static final int flOF = 0x0800;

    static final int INITIAL_CS = 0xf000;
    static final int INITIAL_IP = 0xfff0;
    static final int INITIAL_FLAGS = flORMASK;

    static final int prfREPNZ = 0xf2;
    static final int prfREP =   0xf3;

    private int[] reg;              // general purpose registers
    private int[] sreg;             // segment registers
    private int ip;                 // instruction pointer
    private int flags;              // flags
    private int cycl;               // local clock cycle counter
    private boolean halted;         // halted until next interrupt
    private boolean intsEnabled;    // interrupts enabled
    private boolean trapEnabled;    // single step trap enabled
    private boolean trapSkipFirst;  // skip first instruction after trap enable

    private int csbase;             // CS << 4
    private int nextip;             // new IP after current instruction
    private int jumpip;             // current instruction jumped to new IP
    private int modrm;              // modRM byte for current instruction
    private int insnprf;            // repeat prefix for current insn
    private int insnreg;            // modRM reg field for current insn
    private int insnseg;            // segment register for current insn
    private int insnaddr;           // effective address for current insn

    private volatile boolean reschedule;
    private long cyclesPerSecond = 4772700 * 1;     // CPU clock speed
    private long leftCycleFrags;

    private Scheduler sched;
    private Memory mem;
    private IOPorts io;
    private InterruptController pic;
    private InterruptHook inthook[];
    private TraceHook tracehook;


    /**
     * An InvalidOpcodeException is thrown when the CPU runs into
     * an undefined opcode.
     */
    @SuppressWarnings("serial")
    public class InvalidOpcodeException extends RuntimeException {
        public InvalidOpcodeException(String s) { super(s); }
        public String getMessage() {
            String s1 = super.getMessage();
            String s2 = getStateString();
            return s1 + "\n" + s2.substring(0, s2.length()-1);
        }
        public byte[] getCpuStateData() { return getStateData(); }
        public String getCpuStateString() { return getStateString(); }
    }


    /**
     * An InterruptHook intercepts interrupt handling.
     */
    public interface InterruptHook {
        /**
         * Called when the CPU would have executed an interrupt.
         * @param intno interrupt vector number
         * @param reg registers, may be modified by the hook
         * @param sreg segment registers, may be modified except for CS
         * @param flags single-element array with flags, may be modified
         * @return possibly modified interrupt vector number, or -1 to
         *         suppress invocation of the interrupt
         */
        int interruptHook(int intno, int[] reg, int[] sreg, int[] flags);
    }


    /**
     * A TraceHook is called after execution of each instruction.
     */
    public interface TraceHook {
        void traceHook();
    }


    /**
     * Constructs CPU and reset to initial state
     */
    public Cpu(Scheduler sched, Memory mem, IOPorts io)
    {
        this.sched = sched;
        this.mem = mem;
        this.io = io;
        reg = new int[8];
        sreg = new int[4];
        inthook = new InterruptHook[256];
        reschedule = false;
        reset();
    }


    /**
     * Resets CPU to initial state.
     */
    public void reset()
    {
        for (int i = 0; i < 8; i++)
            reg[i] = 0;
        for (int i = 0; i < 4; i++)
            sreg[i] = 0;
        sreg[sregCS] = INITIAL_CS;
        csbase = INITIAL_CS << 4;
        ip = INITIAL_IP;
        flags = INITIAL_FLAGS;
        intsEnabled = (INITIAL_FLAGS & flIF) != 0;
        trapEnabled = (INITIAL_FLAGS & flTF) != 0;
        trapSkipFirst = false;
        halted = false;
    }


    /**
     * Attaches an InterruptController to this Cpu.
     */
    public void setInterruptController(InterruptController pic)
    {
        this.pic = pic;
    }


    /**
     * Sets the emulated CPU clock rate.
     * @param cps Clock rate in Hz (max 4 GHz)
     */
    public void setCyclesPerSecond(long cps)
    {
        if (cps <= 0 || cps > 4000000000L)
            throw new ArithmeticException("Invalid Cpu clock rate");
        cyclesPerSecond = cps;
        leftCycleFrags = 0;
    }


    /**
     * Returns the emulated CPU clock rate.
     * @return Clock rate in Hz
     */
    public long getCyclesPerSecond()
    {
        return cyclesPerSecond;
    }


    /**
     * (De)Install an interrupt hook.
     * @param h InterruptHook object to install, or null to disable the hook
     * @param v interrupt vector number
     */
    public void setInterruptHook(InterruptHook h, int v)
    {
        inthook[v] = h;
    }


    /**
     * (De)Install the trace hook.
     */
    public void setTraceHook(TraceHook h)
    {
        tracehook = h;
    }


    /**
     * Returns true iff the Cpu is currently halted until the next interrupt.
     */
    public boolean isHalted()
    {
        return halted;
    }


    /**
     * Request that the Cpu return from its current timeslice.
     */
    public void setReschedule()
    {
        reschedule = true;
    }


    /**
     * Returns a binary representation of the CPU state (32 bytes).
     */
    public byte[] getStateData()
    {
        byte[] buf = new byte[32];
        buf[ 0] = (byte)  reg[regAX];
        buf[ 1] = (byte) (reg[regAX] >> 8);
        buf[ 2] = (byte)  reg[regBX];
        buf[ 3] = (byte) (reg[regBX] >> 8);
        buf[ 4] = (byte)  reg[regCX];
        buf[ 5] = (byte) (reg[regCX] >> 8);
        buf[ 6] = (byte)  reg[regDX];
        buf[ 7] = (byte) (reg[regDX] >> 8);
        buf[ 8] = (byte)  reg[regSI];
        buf[ 9] = (byte) (reg[regSI] >> 8);
        buf[10] = (byte)  reg[regDI];
        buf[11] = (byte) (reg[regDI] >> 8);
        buf[12] = (byte)  reg[regBP];
        buf[13] = (byte) (reg[regBP] >> 8);
        buf[14] = (byte)  reg[regSP];
        buf[15] = (byte) (reg[regSP] >> 8);
        buf[16] = (byte)  ip;
        buf[17] = (byte) (ip >> 8);
        buf[18] = (byte)  sreg[sregCS];
        buf[19] = (byte) (sreg[sregCS] >> 8);
        buf[20] = (byte)  sreg[sregDS];
        buf[21] = (byte) (sreg[sregDS] >> 8);
        buf[22] = (byte)  sreg[sregES];
        buf[23] = (byte) (sreg[sregES] >> 8);
        buf[24] = (byte)  sreg[sregSS];
        buf[25] = (byte) (sreg[sregSS] >> 8);
        buf[26] = (byte)  flags;
        buf[27] = (byte) (flags >> 8);
        return buf;
    }


    /**
     * Returns a string description of the CPU state.
     */
    public String getStateString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append(" AX=");  sb.append(Misc.wordToHex(reg[regAX]));
        sb.append("  BX="); sb.append(Misc.wordToHex(reg[regBX]));
        sb.append("  CX="); sb.append(Misc.wordToHex(reg[regCX]));
        sb.append("  DX="); sb.append(Misc.wordToHex(reg[regDX]));
        sb.append("  SI="); sb.append(Misc.wordToHex(reg[regSI]));
        sb.append("  DI="); sb.append(Misc.wordToHex(reg[regDI]));
        sb.append("  BP="); sb.append(Misc.wordToHex(reg[regBP]));
        sb.append("  SP="); sb.append(Misc.wordToHex(reg[regSP]));
        sb.append("\n");
        sb.append(" DS="); sb.append(Misc.wordToHex(sreg[sregDS]));
        sb.append("  ES="); sb.append(Misc.wordToHex(sreg[sregES]));
        sb.append("  SS="); sb.append(Misc.wordToHex(sreg[sregSS]));
        sb.append("  flags="); sb.append(Misc.wordToHex(flags));
        sb.append(" (");
        sb.append((flags & flOF) == 0 ? ' ' : 'O');
        sb.append((flags & flDF) == 0 ? ' ' : 'D');
        sb.append((flags & flIF) == 0 ? ' ' : 'I');
        sb.append((flags & flTF) == 0 ? ' ' : 'T');
        sb.append((flags & flSF) == 0 ? ' ' : 'S');
        sb.append((flags & flZF) == 0 ? ' ' : 'Z');
        sb.append((flags & flAF) == 0 ? ' ' : 'A');
        sb.append((flags & flPF) == 0 ? ' ' : 'P');
        sb.append((flags & flCF) == 0 ? ' ' : 'C');
        sb.append(")  cycl="); sb.append(cycl);
        sb.append("\n");
        sb.append(" CS:IP=");
        sb.append(Misc.wordToHex(sreg[sregCS]));
        sb.append(":");
        sb.append(Misc.wordToHex(ip));
        sb.append(" ");
        for (int i = 0; i < 16; i++) {
            sb.append((ip + i == nextip) ? '|' : ' ');
            sb.append(Misc.byteToHex(
              mem.loadByte((sreg[sregCS] << 4) + ip + i)));
        }
        sb.append("\n");
        return sb.toString();
    }


    // Call an InterruptHook to intercept interrupt handling
    private final int doHook(InterruptHook h, int v)
    {
        flushCycles();
        int[] f = new int[1];
        f[0] = flags;
        int oldcs = sreg[sregCS];
        v = h.interruptHook(v, reg, sreg, f);
        for (int i = 0; i < 8; i++)
            reg[i] &= 0xffff;
        for (int i = 0; i < 4; i++)
            sreg[i] &= 0xffff;
        sreg[sregCS] = oldcs;
        flags = (f[0] & flANDMASK) | flORMASK;
        if (v < -1 || v > 255)
            throw new RuntimeException("invalid vector number");
        return v;
    }


    // Fetch and decode the ModRM byte and compute effective address
    private final void decodeModRm(int modrm)
    {
        nextip++;
        int mod = modrm & 0xc0;
        int rm = modrm & 0x07;
        insnreg = (modrm & 0x38) >> 3;
        switch (mod) {
          case 0x00:
            insnaddr = 0;
            break;
          case 0x40:
            insnaddr = (byte) getImmByte();
            cycl += 4;
            break;
          case 0x80:
            insnaddr = getImmWord();
            cycl += 4;
            break;
          case 0xc0:
            insnaddr = rm | 0x10000;
            return;
        }
        switch (rm) {
          case 0:
            insnaddr += reg[regBX] + reg[regSI];
            if (insnseg == -1) insnseg = sregDS;
            cycl += 7;
            break;
          case 1:
            insnaddr += reg[regBX] + reg[regDI];
            if (insnseg == -1) insnseg = sregDS;
            cycl += 8;
            break;
          case 2:
            insnaddr += reg[regBP] + reg[regSI];
            if (insnseg == -1) insnseg = sregSS;
            cycl += 8;
            break;
          case 3:
            insnaddr += reg[regBP] + reg[regDI];
            if (insnseg == -1) insnseg = sregSS;
            cycl += 7;
            break;
          case 4:
            insnaddr += reg[regSI];
            if (insnseg == -1) insnseg = sregDS;
            cycl += 5;
            break;
          case 5:
            insnaddr += reg[regDI];
            if (insnseg == -1) insnseg = sregDS;
            cycl += 5;
            break;
          case 6:
            if (mod == 0) {
                insnaddr = getImmWord();
                if (insnseg == -1) insnseg = sregDS;
                cycl += 6;
            } else {
                insnaddr += reg[regBP];
                if (insnseg == -1) insnseg = sregSS;
                cycl += 5;
            }
            break;
          case 7:
            insnaddr += reg[regBX];
            if (insnseg == -1) insnseg = sregDS;
            cycl += 5;
            break;
        }
        insnaddr &= 0xffff;
    }


    // Fetch immediate byte operand from current instruction
    private final int getImmByte()
    {
        return mem.loadByte(csbase + (nextip++));
    }


    // Fetch immediate word operand from current instruction
    private final int getImmWord()
    {
        nextip += 2;
        return mem.loadWord(csbase + nextip - 2);
    }


    // Get byte from general register
    private final int getRegByte(int r)
    {
        if ((r & 4) != 0) {
            return reg[r&3] >> 8;
        } else {
            return reg[r&3] & 0xff;
        }
    }


    // Put byte in general register
    private final void putRegByte(int r, int v)
    {
        if ((r & 4) != 0) {
            r &= 3;
            reg[r] = (reg[r] & 0x00ff) | ((v << 8) & 0xff00);
        } else {
            r &= 3;
            reg[r] = (reg[r] & 0xff00) | (v & 0x00ff);
        }
    }


    // Fetch byte from effective address or register
    private final int loadByte()
    {
        if ((insnaddr & 0x10004) == 0x10004) {
            return reg[insnaddr & 3] >> 8;
        } else if ((insnaddr & 0x10000) != 0) {
            return reg[insnaddr & 3] & 0xff;
        } else {
            cycl += 6;
            return mem.loadByte((sreg[insnseg] << 4) + insnaddr);
        }
    }


    // Fetch word from effective address or register
    private final int loadWord()
    {
        if ((insnaddr & 0x10000) != 0) {
            return reg[insnaddr & 7];
        } else {
            cycl += 6;
            return mem.loadWord((sreg[insnseg] << 4) + insnaddr);
        }
    }


    // Store byte to effective address or register
    private final void storeByte(int v)
    {
        if ((insnaddr & 0x10004) == 0x10004) {
            int r = insnaddr & 3;
            reg[r] = (reg[r] & 0x00ff) | ((v << 8) & 0xff00);
        } else if ((insnaddr & 0x10000) != 0) {
            int r = insnaddr & 3;
            reg[r] = (reg[r] & 0xff00) | (v & 0x00ff);
        } else {
            cycl += 7;
            mem.storeByte((sreg[insnseg] << 4) + insnaddr, v);
        }
    }


    // Store word to effective address or register
    private final void storeWord(int v)
    {
        if ((insnaddr & 0x10000) != 0) {
            reg[insnaddr & 7] = v & 0xffff;
        } else {
            cycl += 7;
            mem.storeWord((sreg[insnseg] << 4) + insnaddr, v);
        }
    }


    // Update SF, ZF and PF after 8-bit arithmetic operation
    private final void fixFlagsB(int v)
    {
        int f = flags & (~(flSF | flZF | flPF));
        if ((v & 0x80) != 0) f |= flSF;
        if ((v & 0xff) == 0) f |= flZF;
        v ^= v >> 4; v ^= v >> 2; v ^= v >> 1;
        if ((v & 1) == 0) f |= flPF;
        flags = f;
    }


    // Update SF, ZF and PF after 16-bit arithmetic operation
    private final void fixFlagsW(int v)
    {
        int f = flags & (~(flSF | flZF | flPF));
        if ((v & 0x8000) != 0) f |= flSF;
        if ((v & 0xffff) == 0) f |= flZF;
        v ^= v >> 4; v ^= v >> 2; v ^= v >> 1;
        if ((v & 1) == 0) f |= flPF;
        flags = f;
    }


    // Update SF, ZF, PF, CF, OF and AF after 8-bit addition operation
    private final void fixFlagsAddB(int x, int v, int y) {
        int f = flags & (~(flSF | flZF | flPF | flCF | flOF | flAF));
        if ((y & 0x100) != 0) f |= flCF;
        if (((x^y^v) & 0x10) != 0) f |= flAF;
        if ((((x^(~v))&(x^y)) & 0x80) != 0) f |= flOF;
        if ((y & 0x80) != 0) f |= flSF;
        if ((y & 0xff) == 0) f |= flZF;
        y ^= y >> 4; y ^= y >> 2; y ^= y >> 1;
        if ((y & 1) == 0) f |= flPF;
        flags = f;
    }


    // Update SF, ZF, PF, CF, OF and AF after 16-bit addition operation
    private final void fixFlagsAddW(int x, int v, int y) {
        int f = flags & (~(flSF | flZF | flPF | flCF | flOF | flAF));
        if ((y & 0x10000) != 0) f |= flCF;
        if (((x^y^v) & 0x10) != 0) f |= flAF;
        if ((((x^(~v))&(x^y)) & 0x8000) != 0) f |= flOF;
        if ((y & 0x8000) != 0) f |= flSF;
        if ((y & 0xffff) == 0) f |= flZF;
        y ^= y >> 4; y ^= y >> 2; y ^= y >> 1;
        if ((y & 1) == 0) f |= flPF;
        flags = f;
    }


    // Advance simulation clock to account for used cycles
    private final void flushCycles()
    {
        long t = ((long) cycl) * Scheduler.CLOCKRATE;
        t += leftCycleFrags;
        if (sched != null)
            sched.advanceTime(t / cyclesPerSecond);
        cycl = 0;
        leftCycleFrags = t % cyclesPerSecond;
        reschedule = true;
    }


    // Check for pending hardware interrupt and handle it.
    // Return false if the PIC has no interupt pending, true otherwise.
    private final boolean checkInterrupt()
    {
        if (intsEnabled) {
            if (pic != null) {
                int intno = pic.getPendingInterrupt();
                if (intno >= 0) {
                    halted = false;
                    opAltInt(intno & 0xff);
                    return true;
                }
            }
            return false;
        }
        return true;
    }


    /**
     * Runs the CPU simulation until the scheduled time of the next event.
     * Also checks and handles pending hardware interrupts.
     */
    public void exec()
      throws InvalidOpcodeException
    {
        // Clear reschedule flag
        // This must be done before the call to getTimeToNextEvent()
        reschedule = false;

        // Calculate maximum number of cycles for this run
        long maxRunTime = sched.getTimeToNextEvent();
        if (maxRunTime > Scheduler.CLOCKRATE)
            maxRunTime = Scheduler.CLOCKRATE;
        int maxRunCycl = (int) (
          (maxRunTime * cyclesPerSecond - leftCycleFrags +
           Scheduler.CLOCKRATE - 1) / Scheduler.CLOCKRATE);

        // There may be a new hardware interrupt pending
        boolean maybePendingInterrupt = checkInterrupt();

        // Don't run if the CPU is halted
        if (halted)
            reschedule = true;

        // Special exec loop in case tracing is enabled
        while (tracehook != null && cycl < maxRunCycl && !reschedule) {
            if (maybePendingInterrupt)
                maybePendingInterrupt = checkInterrupt();
            execNext();
            tracehook.traceHook();
        }

        // Run a limited number of cycles
        while (cycl < maxRunCycl && !reschedule) {
            if (maybePendingInterrupt)
                maybePendingInterrupt = checkInterrupt();
            execNext();
        }

        // Update time in scheduler
        if (cycl > 0)
            flushCycles();
    }

    private void aluOps(Operation op) {
      switch (op.b & 0xf9) {
        case 0x00: aluAddB(op.v); break;
        case 0x01: aluAddW(op.v); break;
        case 0x08: aluOrB(op.v);  break;
        case 0x09: aluOrW(op.v);  break;
        case 0x10: aluAdcB(op.v); break;
        case 0x11: aluAdcW(op.v); break;
        case 0x18: aluSbbB(op.v); break;
        case 0x19: aluSbbW(op.v); break;
        case 0x20: aluAndB(op.v); break;
        case 0x21: aluAndW(op.v); break;
        case 0x28: aluSubB(op.v); break;
        case 0x29: aluSubW(op.v); break;
        case 0x30: aluXorB(op.v); break;
        case 0x31: aluXorW(op.v); break;
        case 0x38: aluCmpB(op.v); break;
        case 0x39: aluCmpW(op.v); break;
      }
    }

    /**
     * Decodes and executes one instruction.
     * CPU exceptions and software interrupts are handled by execNext(),
     * but hardware interrupts are handled separately outside this function.
     * 
     * @see http://www.mathemainzel.info/files/x86asmref.html
     */
    public void execNext()
      throws InvalidOpcodeException
    {
        nextip = ip;
        jumpip = -1;
        insnprf = -1;
        insnseg = -1;
        intsEnabled = (flags & flIF) != 0;
        trapEnabled = ((flags & flTF) != 0 && !trapSkipFirst);
        trapSkipFirst = false;
        
        boolean prefix_byte_flag = false;

        while (true) { // Restart here after decoding a prefix byte

            // Check for instruction crossing segment boundary
            if ((nextip & 0xffff0000) != 0)
                throw new InvalidOpcodeException(
                  "Instruction crossing segment limit");
            
            int addr = csbase + nextip++;
            Operation op = mem.loadOp(addr);
            if (op != null) {
              op.exec();
              break;
            }

            // Fetch opcode and possible modRM byte
            int codeword = mem.loadWord(addr);
            int b = codeword & 0xff;
            modrm = codeword >> 8;
            
            prefix_byte_flag = false;

            if (b < 0x40 && (b & 0x07) < 6) {

                // General ALU operations
                switch (b & 0x07) {
                  case 0: // Eb,Gb
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        v = getRegByte(insnreg);
                        cycl += 3;
                        
                        aluOps(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 1: // Ev,Gv
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        v = reg[insnreg];
                        cycl += 3;
                        
                        aluOps(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 2: // Gb,Eb
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        v = loadByte();
                        insnaddr = insnreg | 0x10000;
                        cycl += 3;
                        
                        aluOps(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 3: // Gv,Ev
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        v = loadWord();
                        insnaddr = insnreg | 0x10000;
                        cycl += 3;
                        
                        aluOps(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 4: // AL,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        // v = getImmByte() without++
                        v = mem.loadByte(csbase + nextip);
                        v1 = regAX | 0x10000;
                      }
                      
                      @Override
                      public void exec() {
                        ++nextip;
                        
                        insnaddr = v1; // regAX | 0x10000;
                        cycl += 4;
                        
                        aluOps(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 5: // AX,Iv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        // v = getImmWord() without++
                        v = mem.loadWord(csbase + nextip);
                        v1 = regAX | 0x10000;
                      }
                      
                      @Override
                      public void exec() {
                        ++nextip;
                        ++nextip;
                        
                        insnaddr = v1; // regAX | 0x10000;
                        cycl += 4;
                        
                        aluOps(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  default:
                    throw new RuntimeException("Cpu.java: cannot happen");
                }

            } else if ((b >= 0x40 && b < 0x80) || (b & 0xf0) == 0xb0) {

                // Instructions with operand encoded in first byte
                switch (b & 0xf8) {
                  case 0x40: // INC Gv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = 0x10000 | (b & 7);
                      }
                      
                      @Override
                      public void exec() {
                        insnaddr = v; // 0x10000 | (b & 7);
                        opIncW();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x48: // DEC Gv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = 0x10000 | (b & 7);
                      }
                      
                      @Override
                      public void exec() {
                        insnaddr = v; // 0x10000 | (b & 7);
                        opDecW();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x50: // PUSH Gv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = b & 7;
                      }
                      
                      @Override
                      public void exec() {
                        if (v /*(b & 7)*/ == regSP)
                            opPushW(reg[regSP] - 2);  // 8086 specific semantics
                        else
                            opPushW(reg[b&7]);
                        
                        cycl += 11;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x58: // POP Gv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = b & 7;
                      }
                      
                      @Override
                      public void exec() {
                        reg[v /*b&7*/] = opPopW();
                        cycl += 8;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x60:
                  case 0x68:
                    throw new InvalidOpcodeException("Undefined opcode");
                  case 0x70: // Jcc
                  case 0x78: // Jcc
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = b & 0x0f;
                      }
                      
                      @Override
                      public void exec() {
                        opJccB(v /*b & 0x0f*/);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xb0: // MOV Gb,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = b & 7;
                      }
                      
                      @Override
                      public void exec() {
                        putRegByte(v /*b&7*/, getImmByte());
                        cycl += 4;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xb8: // MOV Gv,Iv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = b & 7;
                      }
                      
                      @Override
                      public void exec() {
                        reg[v /*b&7*/] = getImmWord();
                        cycl += 4;
                      }
                    });
                    
                    op.exec();
                    break;
                }

            } else {

                // All other cases
                switch (b) {
                  case 0x06: // PUSH ES
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opPushW(sreg[sregES]);
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x07: // POP ES
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        sreg[sregES] = opPopW();
                        cycl += 8;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x0e: // PUSH CS
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opPushW(sreg[sregCS]);
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x16: // PUSH SS
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opPushW(sreg[sregSS]);
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x17: // POP SS
                    sreg[sregSS] = opPopW();
                    cycl += 8;
                    insnprf = insnseg = -1;
                    // block interrupts after loading SS
                    prefix_byte_flag = true;
                    break;
                  case 0x1e: // PUSH DS
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opPushW(sreg[sregDS]);
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x1f: // POP DS
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        sreg[sregDS] = opPopW();
                        cycl += 8;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x26: // ES: prefix
                    insnseg = sregES;
                    cycl += 2;
                    prefix_byte_flag = true;
                    break;
                  case 0x27: // DAA
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opDAA();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x2e: // CS: prefix
                    insnseg = sregCS;
                    cycl += 2;
                    prefix_byte_flag = true;
                    break;
                  case 0x2f: // DAS
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opDAS();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x36: // SS: prefix */
                    insnseg = sregSS;
                    cycl += 2;
                    prefix_byte_flag = true;
                    break;
                  case 0x37: // AAA
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opAAA();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x3e: // DS: prefix
                    insnseg = sregDS;
                    cycl += 2;
                    prefix_byte_flag = true;
                    break;
                  case 0x3f: // AAS
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opAAS();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x80: // Grp1 Eb,Ib
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        doGrp1B(getImmByte());
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x81: // Grp1 Ev,Iv
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        doGrp1W(getImmWord());
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x82: // Grp1 Eb,Ib
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        doGrp1B(getImmByte());
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x83: // Grp1 Ev,SignExtend(Ib)
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        doGrp1W(((byte)getImmByte()) & 0xffff);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x84: // TEST Eb,Gb
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        aluTestB(getRegByte(insnreg));
                        cycl += 6;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x85: // TEST Ev,Gv
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        aluTestW(reg[insnreg]);
                        cycl += 6;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x86: // XCHG
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opXchgB(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x87: // XCHG
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opXchgW(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x88: // MOV Eb,Gb
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        storeByte(getRegByte(insnreg));
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x89: // MOV Ev,Gv
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        storeWord(reg[insnreg]);
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x8a: // MOV Gb,Eb
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        putRegByte(insnreg, loadByte());
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x8b: // MOV Gv,Ev
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        reg[insnreg] = loadWord();
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x8c: // MOV Ew,Sw
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        storeWord(sreg[insnreg&3]);
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x8d: // LEA
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opLea(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x8e: // MOV Sw,Ew
                    decodeModRm(modrm);
                    sreg[insnreg&3] = loadWord();
                    cycl += 2;
                    if ((insnreg & 3) == sregCS)
                        csbase = sreg[sregCS] << 4;
                    if ((insnreg & 3) == sregSS) {
                        insnprf = insnseg = -1;
                        // block interrupts after loading SS
                        prefix_byte_flag = true;
                    }
                    break;
                  case 0x8f: // POP Ev
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        decodeModRm(modrm);
                        storeWord(opPopW());
                        cycl += 8;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x90: case 0x91: case 0x92: case 0x93: // XCHG AX,Gv
                  case 0x94: case 0x95: case 0x96: case 0x97: // XCHG AX,Gv
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                        v = b&7;
                      }
                      
                      @Override
                      public void exec() {
                        opXchgAX(v/*b&7*/);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x98: // CBW
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        reg[regAX] = ((byte)reg[regAX]) & 0xffff;
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x99: // CWD
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        reg[regDX] = ( - (reg[regAX] >> 15) ) & 0xffff;
                        cycl += 5;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x9a: // CALL
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opCallFar();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x9b: // WAIT
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        cycl += 4;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x9c: // PUSHF
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opPushW(flags);
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x9d: // POPF
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        // A real 8086 does not trap the instruction immediately
                        // after the POPF that enabled TF, so we do the same thing
                        // (although modern processors do trap the first insn).
                        trapSkipFirst = ((flags & flTF) == 0);
                        flags = (opPopW() & flANDMASK) | flORMASK;
                        cycl += 8;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x9e: // SAHF
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        flags = ((reg[regAX] >> 8) & 0x00ff & flANDMASK) |
                                (flags & 0xff00) | flORMASK;
                        cycl += 4;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0x9f: // LAHF
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        putRegByte(regAX|4, flags & 0xff);
                        cycl += 4;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xa0: /* MOV  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opMovBAccMem();
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xa1: /* MOV  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() {
                        opMovWAccMem();
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xa2: /* MOV  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opMovBMemAcc();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xa3: /* MOV  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opMovWMemAcc();
                      }
                    });
                    
                    op.exec();  
                    break;
                  case 0xa4: // MOVSB
                  case 0xa5: // MOVSW
                  case 0xa6: // CMPSB
                  case 0xa7: // CMPSW
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doString(b);
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xa8: // TEST AL,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = regAX | 0x10000;
                        v1 = mem.loadByte(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        insnaddr = v; // regAX | 0x10000;
                        ++nextip;
                        aluTestB(v1/*getImmByte()*/);
                        cycl += 4;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xa9: // TEST AX,Iv
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = regAX | 0x10000;
                        v1 = mem.loadWord(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        insnaddr = v; // regAX | 0x10000;
                        ++nextip;
                        ++nextip;
                        aluTestW(v1/*getImmWord()*/);
                        cycl += 4;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xaa: // STOSB
                  case 0xab: // STOSW
                  case 0xac: // LODSB
                  case 0xad: // LODSW
                  case 0xae: // SCASB
                  case 0xaf: // SCASW
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doString(b);
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xc2: /* RET  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = mem.loadWord(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        ++nextip;
                        ++nextip;
                        opRetNear(v/*getImmWord()*/); 
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xc3: /* RET  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opRetNear(0); 
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xc4: /* LES  */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opLoadPtr(this, sregES);
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xc5: /* LDS  */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opLoadPtr(this, sregDS);
                      }
                    });
                    
                    op.exec();  
                    break;
                  case 0xc6: // MOV Eb,Ib
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        decodeModRm(modrm);
                        storeByte(getImmByte());
                        cycl += 3;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xc7: // MOV Ev,Iv
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        decodeModRm(modrm);
                        storeWord(getImmWord());
                        cycl += 3;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xca: /* RETF */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                        v = mem.loadWord(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        ++nextip;
                        ++nextip;
                        opRetFar(v/*getImmWord()*/);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xcb: /* RETF */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opRetFar(0);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xcc: /* INT3 */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opInt(3);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xcd: /* INT  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = mem.loadByte(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        ++nextip;
                        opInt(v/*getImmByte()*/);
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xce: // INTO
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        if ((flags & flOF) != 0)
                          opInt(4);
                        cycl += 4;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xcf: /* IRET */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opIret();
                      }
                    });
                    
                    op.exec();  
                    break;
                  case 0xd0: /* Grp2 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp2B(this, false);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd1: /* Grp2 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp2W(this, false);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd2: /* Grp2 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp2B(this, true); 
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd3: /* Grp2 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp2W(this, true);
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xd4: /* AAM  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opAAM();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd5: /* AAD  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opAAD();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd6: // SALC
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        putRegByte(regAX, ((flags&flCF) == 0) ? 0x00 : 0xff);
                        cycl += 4;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd7: /* XLAT */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opXlatB();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xd8: case 0xd9: case 0xda: case 0xdb: // ESC r/m
                  case 0xdc: case 0xdd: case 0xde: case 0xdf: // ESC r/m
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        decodeModRm(modrm);
                        // ignore coprocessor instruction
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe0: /* LOOPNZ */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opLoop((flags & flZF) == 0);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe1: /* LOOPZ  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opLoop((flags & flZF) != 0);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe2: /* LOOP   */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opLoop(true);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe3: /* JCXZ */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opJcxz();
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xe4: // IN AL,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        putRegByte(regAX, io.inb(getImmByte()));
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe5: // IN AX,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        reg[regAX] = io.inw(getImmByte());
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe6: // OUT AL,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        io.outb(reg[regAX] & 0xff, getImmByte());
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe7: // OUT AX,Ib
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        io.outw(reg[regAX], getImmByte());
                        cycl += 10;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe8: /* CALL */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = mem.loadWord(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        ++nextip;
                        ++nextip;
                        opCallRel(v/*getImmWord()*/);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xe9: /* JMP  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = mem.loadWord(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        ++nextip;
                        ++nextip;
                        opJmpRel(v/*getImmWord()*/);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xea: /* JMP  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        opJmpFar();
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xeb: /* JMP  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                        v = mem.loadByte(csbase + nextip);
                      }
                      
                      @Override
                      public void exec() { 
                        ++nextip;
                        opJmpRel((byte)v/*getImmByte()*/);
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xec: // IN AL,DX
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        putRegByte(regAX, io.inb(reg[regDX]));
                        cycl += 8;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xed: // IN AX,DX
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        reg[regAX] = io.inw(reg[regDX]);
                        cycl += 8;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xee: // OUT AL,DX
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        io.outb(reg[regAX] & 0xff, reg[regDX]);
                        cycl += 8;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xef: // OUT AX,DX
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flushCycles();
                        io.outw(reg[regAX], reg[regDX]);
                        cycl += 8;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xf0: /* LOCK */
                    cycl += 2; 
                    prefix_byte_flag = true;
                    break;
                  case 0xf2: /* REPNZ*/ 
                    insnprf = b; 
                    cycl += 2; 
                    prefix_byte_flag = true;
                    break;
                  case 0xf3: /* REP  */ 
                    insnprf = b; 
                    cycl += 2; 
                    prefix_byte_flag = true;
                    break;
                  case 0xf4: /* HLT  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        halted = true;
                        reschedule = true;
                        cycl += 2;
                        // We don't trap after HLT instructions
                        // even though a real 8086 would (modern processors don't)
                        trapEnabled = false;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xf5: /* CMC  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flags ^= flCF; 
                        cycl += 2; 
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xf6: /* Grp3 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp3B(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xf7: /* Grp3 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp3W(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xf8: /* CLC  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flags &= ~ flCF; 
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xf9: /* STC  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flags |= flCF;   
                        cycl += 2;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xfa: /* CLI  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        // CLI disabled interrupts immediately
                        flags &= ~ flIF;
                        intsEnabled = false;
                        cycl += 2;
                      }
                    });
                    
                    op.exec(); 
                    break;
                  case 0xfb: /* STI  */
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        // STI enables interrupts after the next instruction
                        flags |= flIF;
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xfc: /* CLD  */ 
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flags &= ~ flDF; 
                        cycl += 2;
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xfd: /* STD  */ 
                    mem.storeOp(addr, op = new Operation(b) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        flags |= flDF;   
                        cycl += 2; 
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xfe: /* Grp4 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp4(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  case 0xff: /* Grp5 */
                    mem.storeOp(addr, op = new Operation(b, modrm) {
                      @Override
                      protected void init() {
                      }
                      
                      @Override
                      public void exec() { 
                        doGrp5(this);
                      }
                    });
                    
                    op.exec();
                    break;
                  default:
                    throw new InvalidOpcodeException("Undefined opcode");
                }

            }

            if (!prefix_byte_flag)
              break; // done with the current instruction (breaks while)

        } // while(true)

        // Check for instruction crossing segment boundary
        if ((nextip & 0xffff0000) != 0)
            throw new InvalidOpcodeException(
              "Instruction crossing segment limit");

        // Update instruction pointer
        ip = (jumpip == -1) ? nextip : jumpip;

        // Handle single step trap
        if (trapEnabled)
            opAltInt(1);
    }


    // Group 1 (binary arithmetic) on byte
    private final void doGrp1B(int v) {
        switch (insnreg) {
          case 0: aluAddB(v); break;
          case 1: aluOrB(v);  break;
          case 2: aluAdcB(v); break;
          case 3: aluSbbB(v); break;
          case 4: aluAndB(v); break;
          case 5: aluSubB(v); break;
          case 6: aluXorB(v); break;
          case 7: aluCmpB(v); break;
        }
        cycl += 4;
    }

    // Group 1 (binary arithmetic) on word
    private final void doGrp1W(int v) {
        switch (insnreg) {
          case 0: aluAddW(v); break;
          case 1: aluOrW(v);  break;
          case 2: aluAdcW(v); break;
          case 3: aluSbbW(v); break;
          case 4: aluAndW(v); break;
          case 5: aluSubW(v); break;
          case 6: aluXorW(v); break;
          case 7: aluCmpW(v); break;
        }
        cycl += 4;
    }

    // Group 2 (shift/rotate) on byte
    private final void doGrp2B(Operation op, boolean usecl) {
        int x, y, count = 1;
        cycl += 2;
        if (usecl) {
            count = reg[regCX] & 0xff;
            cycl += 5 + 4 * count;
        }
        decodeModRm(op.modrm);
        x = loadByte();
        if (count == 0)
            return;
        switch (insnreg) {
          case 0: // ROL
            y = (x << (count&7)) | (x >> (8-(count&7)));
            if ((y & 1) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 1: // ROR
            y = (x >> (count&7)) | (x << (8-(count&7)));
            if ((y & 0x80) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 2: // RCL
            if (count > 8) { count = count % 9; y = x; if (count == 0) break; }
            y = (x << count) | (x >> (9-count));
            if ((flags & flCF) != 0) y |= 1 << (count-1);
            if ((y & 0x100) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 3: // RCR
            if (count > 8) { count = count % 9; y = x; if (count == 0) break; }
            y = (x >> count) | (x << (9-count));
            if ((flags & flCF) != 0) y |= 1 << (8-count);
            if (((x >> (count-1)) & 1) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 4: // SHL
          case 6: // SAL
            if (count > 24) count = 24;
            y = x << count;
            if ((y & 0x100) != 0) flags |= flCF; else flags &= ~ flCF;
            if ((y & 0x10) != 0) flags |= flAF; else flags &= ~flAF;
            fixFlagsB(y);
            break;
          case 5: // SHR
            if (count > 24) count = 24;
            y = x >> count;
            if (((x >> (count-1)) & 1) != 0)
                flags |= flCF;
            else
                flags &= ~ flCF;
            flags &= ~flAF;
            fixFlagsB(y);
            break;
          case 7: // SAR
            if (count > 8) count = 8;
            y = x;
            if ((y & 0x80) != 0) y |= 0xff00;
            if (((y >> (count-1)) & 1) != 0)
                flags |= flCF;
            else
                flags &= ~ flCF;
            y = y >> count;
            flags &= ~flAF;
            fixFlagsB(y);
            break;
          default:
            throw new RuntimeException("Cpu.java: cannot happen");
        }
        storeByte(y);
        if (insnreg == 7) {
            // SAR: set OF = 0
            flags &= ~ flOF;
        } else if ((insnreg & 1) == 0) {
            // fix overflow after left shift/rotate:
            // OF = CF XOR (result bit 7)
            flags &= ~ flOF;
            flags |= ((flags << 11) ^ (y << 4)) & flOF;
        } else {
            // fix overflow after right shift/rotate:
            // OF = (result bit 6) XOR (result bit 7)
            flags &= ~ flOF;
            flags |= ((y << 4) ^ (y << 5)) & flOF;
        }
    }

    // Group 2 (shift/rotate) on word
    private final void doGrp2W(Operation op, boolean usecl) {
        int x, y, count = 1;
        cycl += 2;
        if (usecl) {
            count = reg[regCX] & 0xff;
            cycl += 5 + 4 * count;
        }
        decodeModRm(op.modrm);
        x = loadWord();
        if (count == 0)
            return;
        switch (insnreg) {
          case 0: // ROL
            y = (x << (count&15)) | (x >> (16-(count&15)));
            if ((y & 1) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 1: // ROR
            y = (x >> (count&15)) | (x << (16-(count&15)));
            if ((y & 0x8000) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 2: // RCL
            if (count > 16) {
                count = count % 17;
                y = x;
                if (count == 0) break;
            }
            y = (x << count) | (x >> (17-count));
            if ((flags & flCF) != 0) y |= 1 << (count-1);
            if ((y & 0x10000) != 0) flags |= flCF; else flags &= ~ flCF;
            break;
          case 3: // RCR
            if (count > 16) {
                count = count % 17;
                y = x;
                if (count == 0) break;
            }
            y = (x >> count) | (x << (17-count));
            if ((flags & flCF) != 0) y |= 1 << (16-count);
            if (((x >> (count-1)) & 1) != 0)
                flags |= flCF;
            else
                flags &= ~ flCF;
            break;
          case 4: // SHL
          case 6: // SAL
            if (count > 24) count = 24;
            y = x << count;
            if ((y & 0x10000) != 0) flags |= flCF; else flags &= ~ flCF;
            if ((y & 0x10) != 0) flags |= flAF; else flags &= ~flAF;
            fixFlagsW(y);
            break;
          case 5: // SHR
            if (count > 24) count = 24;
            y = x >> count;
            if (((x >> (count-1)) & 1) != 0)
                flags |= flCF;
            else
                flags &= ~ flCF;
            flags &= ~flAF;
            fixFlagsW(y);
            break;
          case 7: // SAR
            if (count > 16) count = 16;
            y = x;
            if ((y & 0x8000) != 0) y |= 0xffff0000;
            if (((y >> (count-1)) & 1) != 0)
                flags |= flCF;
            else
                flags &= ~ flCF;
            y = y >> count;
            flags &= ~flAF;
            fixFlagsW(y);
            break;
          default:
            throw new RuntimeException("Cpu.java: cannot happen");
        }
        storeWord(y);
        if (insnreg == 7) {
            // SAR: set OF = 0
            flags &= ~flOF;
        } else if ((insnreg & 1) == 0) {
            // fix overflow after left shift/rotate:
            // OF = CF XOR (result bit 15)
            flags &= ~ flOF;
            flags |= ((flags << 11) ^ (y >> 4)) & flOF;
        } else {
            // fix overflow after right shift/rotate:
            // OF = (result bit 14) XOR (result bit 15)
            flags &= ~ flOF;
            flags |= ((y >> 4) ^ (y >> 3)) & flOF;
        }
    }

    // Group 3 (unary arithmetic / immediate test) on byte
    private final void doGrp3B(Operation op) throws InvalidOpcodeException {
        decodeModRm(op.modrm);
        switch (insnreg) {
          case 0: aluTestB(getImmByte()); cycl += 5; break;
          case 1: throw new InvalidOpcodeException("Undefined opcode");
          case 2: /*NOT*/ storeByte( ~ loadByte() ); cycl += 3; break;
          case 3: /*NEG*/ {
            int x = loadByte();
            int y = 0 - x;
            storeByte(y);
            flags &= ~ (flCF | flAF | flOF);
            if (x != 0) flags |= flCF;
            if ((x & 0x0f) != 0) flags |= flAF;
            if (x == 0x80) flags |= flOF;
            fixFlagsB(y);
            cycl += 3;
            break; }
          case 4: opMulB(); break;
          case 5: opIMulB(); break;
          case 6: opDivB(); break;
          case 7: opIDivB(); break;
        }
    }

    // Group 3 (unary arithmetic / immediate test) on word
    private final void doGrp3W(Operation op) throws InvalidOpcodeException {
        decodeModRm(op.modrm);
        switch (insnreg) {
          case 0: aluTestW(getImmWord()); cycl += 5; break;
          case 1: throw new InvalidOpcodeException("Undefined opcode");
          case 2: /*NOT*/ storeWord( ~ loadWord() ); cycl += 3; break;
          case 3: /*NEG*/ {
            int x = loadWord();
            int y = 0 - x;
            storeWord(y);
            flags &= ~ (flCF | flAF | flOF);
            if (x != 0) flags |= flCF;
            if ((x & 0x0f) != 0) flags |= flAF;
            if (x == 0x8000) flags |= flOF;
            fixFlagsW(y);
            cycl += 3;
            break; }
          case 4: opMulW(); break;
          case 5: opIMulW(); break;
          case 6: opDivW(); break;
          case 7: opIDivW(); break;
        }
    }

    // Group 4 (increment/decrement on byte)
    private final void doGrp4(Operation op) throws InvalidOpcodeException {
        decodeModRm(op.modrm);
        switch (insnreg) {
          case 0: opIncB(); break;
          case 1: opDecB(); break;
          default: throw new InvalidOpcodeException("Undefined opcode");
        }
    }

    // Group 5 (misc)
    private final void doGrp5(Operation op) throws InvalidOpcodeException {
        decodeModRm(op.modrm);
        switch (insnreg) {
          case 0: opIncW(); break;
          case 1: opDecW(); break;
          case 2: opCallNearInd(); break;
          case 3: opCallFarInd(); break;
          case 4: opJmpNearInd(); break;
          case 5: opJmpFarInd(); break;
          case 6:
            if (insnaddr == (0x10000 | regSP))
                opPushW(reg[regSP] - 2); // 8086 specific semantics
            else
                opPushW(loadWord());
                cycl += 10;
            break;
          case 7: throw new InvalidOpcodeException("Undefined opcode");
        }
    }

    // String operations
    private final void doString(int opcode) {
        int si = reg[regSI];
        int di = reg[regDI];
        if (insnseg == -1) insnseg = sregDS;
        int srcaddr = (sreg[insnseg] << 4) + si;
        int dstaddr = (sreg[sregES] << 4) + di;
        int ptrinc  = ((flags & flDF) == 0 ? 1 : -1) << (opcode & 1);
        boolean srcinc = false, dstinc = false;
        int count = reg[regCX];
        boolean rep = true;
        int x, y;

        // Check repeat counter
        if (count == 0 && insnprf != -1)
            return;

        // Primitive string operation
        switch (opcode) {
          case 0xa4: /* MOVSB */
            mem.storeByte(dstaddr, mem.loadByte(srcaddr));
            srcinc = true; dstinc = true;
            cycl += 18;
            break;
          case 0xa5: /* MOVSW */
            mem.storeWord(dstaddr, mem.loadWord(srcaddr));
            srcinc = true; dstinc = true;
            cycl += 18;
            break;
          case 0xa6: /* CMPSB */
            x = mem.loadByte(srcaddr);
            y = mem.loadByte(dstaddr);
            fixFlagsAddB(x, y^0x80, (x - y));
            srcinc = true; dstinc = true;
            rep = (insnprf == prfREP) ? (x == y) : (x != y);
            cycl += 22;
            break;
          case 0xa7: /* CMPSW */
            x = mem.loadWord(srcaddr);
            y = mem.loadWord(dstaddr);
            fixFlagsAddW(x, y^0x8000, (x - y));
            srcinc = true; dstinc = true;
            rep = (insnprf == prfREP) ? (x == y) : (x != y);
            cycl += 22;
            break;
          case 0xaa: /* STOSB */
            mem.storeByte(dstaddr, reg[regAX]);
            dstinc = true;
            cycl += 11;
            break;
          case 0xab: /* STOSW */
            mem.storeWord(dstaddr, reg[regAX]);
            dstinc = true;
            cycl += 11;
            break;
          case 0xac: /* LODSB */
            reg[regAX] = (reg[regAX] & 0xff00) | mem.loadByte(srcaddr);
            srcinc = true;
            cycl += 12;
            break;
          case 0xad: /* LODSW */
            reg[regAX] = mem.loadWord(srcaddr);
            srcinc = true;
            cycl += 12;
            break;
          case 0xae: /* SCASB */
            x = reg[regAX] & 0xff;
            y = mem.loadByte(dstaddr);
            fixFlagsAddB(x, y^0x80, (x - y));
            dstinc = true;
            rep = (insnprf == prfREP) ? (x == y) : (x != y);
            cycl += 15;
            break;
          case 0xaf: /* SCASW */
            x = reg[regAX];
            y = mem.loadWord(dstaddr);
            fixFlagsAddW(x, y^0x8000, (x - y));
            dstinc = true;
            rep = (insnprf == prfREP) ? (x == y) : (x != y);
            cycl += 15;
            break;
        }

        // Update pointers
        if (srcinc) reg[regSI] = (si + ptrinc) & 0xffff;
        if (dstinc) reg[regDI] = (di + ptrinc) & 0xffff;

        // Handle repeat prefix
        if (insnprf != -1) {
            count--;
            reg[regCX] = count;
            if (count != 0 && rep) jumpip = ip;
        }
    }

    private final void aluAddB(int v) {
        int x = loadByte();
        int y = x + v;
        storeByte(y);
        fixFlagsAddB(x, v, y);
    }

    private final void aluAddW(int v) {
        int x = loadWord();
        int y = x + v;
        storeWord(y);
        fixFlagsAddW(x, v, y);
    }

    private final void aluOrB(int v) {
        int x = loadByte();
        int y = x | v;
        storeByte(y);
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsB(y);
    }

    private final void aluOrW(int v) {
        int x = loadWord();
        int y = x | v;
        storeWord(y);
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsW(y);
    }

    private final void aluAdcB(int v) {
        int x = loadByte();
        int y = x + v;
        if ((flags & flCF) != 0) y++;
        storeByte(y);
        fixFlagsAddB(x, v, y);
    }

    private final void aluAdcW(int v) {
        int x = loadWord();
        int y = x + v;
        if ((flags & flCF) != 0) y++;
        storeWord(y);
        fixFlagsAddW(x, v, y);
    }

    private final void aluSbbB(int v) {
        int x = loadByte();
        int y = x - v;
        if ((flags & flCF) != 0) y--;
        storeByte(y);
        fixFlagsAddB(x, v^0x80, y);
    }

    private final void aluSbbW(int v) {
        int x = loadWord();
        int y = x - v;
        if ((flags & flCF) != 0) y--;
        storeWord(y);
        fixFlagsAddW(x, v^0x8000, y);
    }

    private final void aluAndB(int v) {
        int x = loadByte();
        int y = x & v;
        storeByte(y);
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsB(y);
    }

    private final void aluAndW(int v) {
        int x = loadWord();
        int y = x & v;
        storeWord(y);
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsW(y);
    }

    private final void aluSubB(int v) {
        int x = loadByte();
        int y = x - v;
        storeByte(y);
        fixFlagsAddB(x, v^0x80, y);
    }

    private final void aluSubW(int v) {
        int x = loadWord();
        int y = x - v;
        storeWord(y);
        fixFlagsAddW(x, v^0x8000, y);
    }

    private final void aluXorB(int v) {
        int x = loadByte();
        int y = x ^ v;
        storeByte(y);
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsB(y);
    }

    private final void aluXorW(int v) {
        int x = loadWord();
        int y = x ^ v;
        storeWord(y);
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsW(y);
    }

    private final void aluCmpB(int v) {
        int x = loadByte();
        int y = x - v;
        fixFlagsAddB(x, v^0x80, y);
    }

    private final void aluCmpW(int v) {
        int x = loadWord();
        int y = x - v;
        fixFlagsAddW(x, v^0x8000, y);
    }

    private final void aluTestB(int v) {
        int x = loadByte();
        int y = x & v;
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsB(y);
    }

    private final void aluTestW(int v) {
        int x = loadWord();
        int y = x & v;
        flags &= ~ (flOF | flCF);
        flags &= ~ flAF; // officially undefined
        fixFlagsW(y);
    }

    // Push word on the stack
    private final void opPushW(int v) {
        int sp = (reg[regSP] - 2) & 0xffff;
        reg[regSP] = sp;
        mem.storeWord((sreg[sregSS] << 4) + sp, v);
    }

    // Pop word from the stack
    private final int opPopW() {
        int sp = reg[regSP];
        reg[regSP] = (sp + 2) & 0xffff;
        return mem.loadWord((sreg[sregSS] << 4) + sp);
    }

    private final void opAAA() {
        int a = reg[regAX];
        int x = a;
        if (((a&0x000f) > 9) || ((flags&flAF) != 0)) {
            a = ((a + 0x0100) & 0xff00) | ((a + 0x06) & 0xff);
            flags |= flAF | flCF;
        } else {
            flags &= ~ (flAF | flCF);
        }
        reg[regAX] = a & 0xff0f;
        fixFlagsB(a);
        cycl += 8;
    }

    // AAD Ib
    private final void opAAD() {
        int d = getImmByte();
        int x = reg[regAX];
        int v = ((x >> 8) * d) & 0x00ff;
        int y = (x & 0x00ff) + v;
        reg[regAX] = y & 0x00ff;
        fixFlagsAddB(x, v, y);
        cycl += 60;
    }

    // AAM Ib
    private final void opAAM() {
        int d = getImmByte();
        int a = reg[regAX] & 0x00ff;
        cycl += 83;
        if (d == 0) { opInt(0); return; }
        a = ((a / d) << 8) | (a % d);
        reg[regAX] = a;
        flags &= ~ (flCF | flOF | flAF); // officially undocumented
        fixFlagsB(a);
    }

    private final void opAAS() {
        int a = reg[regAX];
        int x = a;
        if (((a&0x000f) > 9) || ((flags&flAF) != 0)) {
            a = ((a - 0x0100) & 0xff00) | ((a - 0x06) & 0xff);
            flags |= flAF | flCF;
        } else {
            flags &= ~(flAF | flCF);
        }
        reg[regAX] = a & 0xff0f;
        fixFlagsB(a);
        cycl += 8;
    }

    // CALL Ap
    private final void opCallFar() {
        opPushW(sreg[sregCS]);
        opPushW(nextip + 4);
        jumpip = getImmWord();
        sreg[sregCS] = getImmWord();
        csbase = sreg[sregCS] << 4;
        cycl += 28;
    }

    // CALL Mp
    private final void opCallFarInd() throws InvalidOpcodeException {
        if ((insnaddr & 0x10000) != 0)
            throw new InvalidOpcodeException("Register operand not allowed");
        opPushW(sreg[sregCS]);
        opPushW(nextip);
        int b = sreg[insnseg] << 4;
        jumpip = mem.loadWord(b + insnaddr);
        sreg[sregCS] = mem.loadWord(b + insnaddr + 2);
        csbase = sreg[sregCS] << 4;
        cycl += 37;
    }

    // CALL Av
    private final void opCallNearInd() {
        opPushW(nextip);
        jumpip = loadWord();
        cycl += 16;
    }

    // CALL Jv
    private final void opCallRel(int disp) {
        opPushW(nextip);
        jumpip = (nextip + disp) & 0xffff;
        cycl += 19;
    }

    private final void opDAA() {
        int a = reg[regAX] & 0x00ff;
        int x = a;
        if ((a > 0x9f) ||
            (a > 0x99 && (flags & flAF) == 0) ||
            ((flags & flCF) != 0)) {
            // the constraint on AF is not in official specs
            a += 0x60;
            flags |= flCF;
        }
        if (((a & 0x0f) > 9) || ((flags & flAF) != 0)) {
            a += 6;
            flags |= flAF;
        }
        reg[regAX] = (reg[regAX] & 0xff00) | (a & 0x00ff);
        fixFlagsB(a);
        // effect on OF is officially undefined
        if ((a & 0x80) > (x & 0x80)) flags |= flOF; else flags &= ~ flOF;
        cycl += 4;
    }

    private final void opDAS() {
        int a = reg[regAX] & 0x00ff;
        int x = a;
        if ((a > 0x9f) ||
            (a > 0x99 && (flags & flAF) == 0) ||
            ((flags & flCF) != 0)) {
            // the constraint on AF is not in official specs
            a -= 0x60;
            flags |= flCF;
        }
        if (((a & 0x0f) > 9) || ((flags & flAF) != 0)) {
            a -= 6;
            flags |= flAF;
        }
        reg[regAX] = (reg[regAX] & 0xff00) | (a & 0x00ff);
        fixFlagsB(a);
        // effect on OF is officially undefined
        if ((a & 0x80) < (x & 0x80)) flags |= flOF; else flags &= ~ flOF;
        cycl += 4;
    }

    private final void opDecB() {
        int y = loadByte() - 1;
        storeByte(y);
        if ((y & 0xff) == 0x7f) flags |= flOF; else flags &= ~ flOF;
        if ((y & 0x0f) == 0x0f) flags |= flAF; else flags &= ~ flAF;
        fixFlagsB(y);
        cycl += 3;
    }

    private final void opDecW() {
        int y = loadWord() - 1;
        storeWord(y);
        if ((y & 0xffff) == 0x7fff) flags |= flOF; else flags &= ~ flOF;
        if ((y & 0x0f) == 0x0f) flags |= flAF; else flags &= ~ flAF;
        fixFlagsW(y);
        cycl += 3;
    }

    private final void opDivB() {
        int x = reg[regAX];
        int y = loadByte();
        cycl += 80;
        if (y == 0) { opInt(0); return; }
        int z = x / y;
        int m = x % y;
        if ((z & 0xff00) != 0) { opInt(0); return; }
        reg[regAX] = ((m & 0xff) << 8) | (z & 0xff);
        // ZF, SF, PF, AF are officially undefined and
        // 8086 behaviour is too complicated to simulate
    }

    private final void opDivW() {
        int x = reg[regAX] | (reg[regDX] << 16);
        int y = loadWord();
        cycl += 144;
        if (y == 0) { opInt(0); return; }
        int z =  ((x >>> 1) / y) << 1;
        int m = (((x >>> 1) % y) << 1) + (x & 1);
        z += m / y;
        m = m % y;
        if ((z & 0xffff0000) != 0) { opInt(0); return; }
        reg[regAX] = z & 0xffff;
        reg[regDX] = m & 0xffff;
        // ZF, SF, PF, AF are officially undefined and
        // 8086 behaviour is too complicated to simulate
    }

    private final void opIDivB() {
        int x = (reg[regAX] << 16) >> 16;
        int y = (byte) loadByte();
        cycl += 101;
        if (y == 0) { opInt(0); return; }
        int z = x / y;
        int m = x % y;
        if ((z & 0xffffff00) + ((z & 0x0080) << 1) != 0) { opInt(0); return; }
        // a real 8086 would also raise INT 0 if z == 0x80
        reg[regAX] = ((m & 0xff) << 8) | (z & 0xff);
        // ZF, SF, PF, AF are officially undefined and
        // 8086 behaviour is too complicated to simulate
    }

    private final void opIDivW() {
        int x = reg[regAX] | (reg[regDX] << 16);
        int y = (loadWord() << 16) >> 16;
        cycl += 165;
        if (y == 0) { opInt(0); return; }
        int z = x / y;
        int m = x % y;
        if ((z & 0xffff0000) + ((z & 0x8000) << 1) != 0) { opInt(0); return; }
        // a real 8086 would also raise INT 0 if z == 0x8000
        reg[regAX] = z & 0xffff;
        reg[regDX] = m & 0xffff;
        // ZF, SF, PF, AF are officially undefined and
        // 8086 behaviour is too complicated to simulate
    }

    private final void opIMulB() {
        int x = (byte) reg[regAX];
        int y = (byte) loadByte();
        int z = x * y;
        reg[regAX] = z & 0xffff;
        if ((z & 0xffffff00) + ((z & 0x80) << 1) != 0)
            flags |= (flCF | flOF);
        else
            flags &= ~ (flCF | flOF);
        cycl += 80;
    }

    private final void opIMulW() {
        int x = (reg[regAX] << 16) >> 16;
        int y = (loadWord() << 16) >> 16;
        int z = x * y;
        reg[regAX] = z & 0xffff;
        reg[regDX] = (z >> 16) & 0xffff;
        if ((z & 0xffff0000) + ((z & 0x8000) << 1) != 0)
            flags |= (flCF | flOF);
        else
            flags &= ~ (flCF | flOF);
        cycl += 128;
    }

    private final void opIncB() {
        int y = loadByte() + 1;
        storeByte(y);
        if ((y & 0xff) == 0x80) flags |= flOF; else flags &= ~ flOF;
        if ((y & 0x0f) == 0) flags |= flAF; else flags &= ~ flAF;
        fixFlagsB(y);
        cycl += 3;
    }

    private final void opIncW() {
        int y = loadWord() + 1;
        storeWord(y);
        if ((y & 0xffff) == 0x8000) flags |= flOF; else flags &= ~ flOF;
        if ((y & 0x0f) == 0) flags |= flAF; else flags &= ~ flAF;
        fixFlagsW(y);
        cycl += 3;
    }

    // Setup interrupt while executing instruction (softint, exception)
    private final void opInt(int v) {
        if (inthook[v] != null) {
            v = doHook(inthook[v], v);
            if (v < 0) return;
        }
        opPushW(flags);
        opPushW(sreg[sregCS]);
        opPushW(nextip);
        flags &= ~ (flIF | flTF);
        intsEnabled = false;
        // A real 8086 would also trap on the first insn of the handler,
        // but we don't (and modern processors also don't).
        trapEnabled = false;
        jumpip = mem.loadWord(4*v);
        sreg[sregCS] = mem.loadWord(4*v + 2);
        csbase = sreg[sregCS] << 4;
        cycl += 51;
    }

    // Setup interrupt while not in instruction context (irq, trap)
    private final void opAltInt(int v) {
        if (inthook[v] != null) {
            v = doHook(inthook[v], v);
            if (v < 0) return;
        }
        opPushW(flags);
        opPushW(sreg[sregCS]);
        opPushW(ip);
        flags &= ~ (flIF | flTF);
        intsEnabled = false;
        // A real 8086 would also trap on the first insn of the handler,
        // but we don't (and modern processors also don't).
        trapEnabled = false;
        ip = mem.loadWord(4*v);
        sreg[sregCS] = mem.loadWord(4*v + 2);
        csbase = sreg[sregCS] << 4;
        cycl += 51;
    }

    private final void opIret() {
        jumpip = opPopW();
        sreg[sregCS] = opPopW();
        csbase = sreg[sregCS] << 4;
        flags = (opPopW() & flANDMASK) | flORMASK;
        cycl += 32;
    }

    // Jcc Jb
    private final void opJccB(int cc) {
        int disp = (byte) getImmByte();
        boolean t;
        switch (cc >> 1) {
          case 0: /*O */ t = ((flags&flOF) != 0); break;
          case 1: /*B */ t = ((flags&flCF) != 0); break;
          case 2: /*Z */ t = ((flags&flZF) != 0); break;
          case 3: /*BE*/ t = ((flags & (flCF|flZF)) != 0); break;
          case 4: /*S */ t = ((flags&flSF) != 0); break;
          case 5: /*P */ t = ((flags&flPF) != 0); break;
          case 6: /*L */ t = ((flags&flSF) != 0) ^ ((flags&flOF) != 0); break;
          case 7: /*LE*/ t = ((flags&flZF) != 0) ||
                             (((flags&flSF) != 0) ^ ((flags&flOF) != 0));
            break;
          default: throw new RuntimeException("Cpu.java: cannot happen");
        }
        t ^= ((cc & 1) != 0);
        cycl += 4;
        if (t) {
            jumpip = (nextip + disp) & 0xffff;
            cycl += 12;
        }
    }

    // JCXZ Jb
    private final void opJcxz() {
        int disp = (byte) getImmByte();
        boolean t = (reg[regCX] == 0);
        cycl += 6;
        if (t) {
            jumpip = (nextip + disp) & 0xffff;
            cycl += 12;
        }
    }

    // JMP Ap
    private final void opJmpFar() {
        jumpip = getImmWord();
        sreg[sregCS] = getImmWord();
        csbase = sreg[sregCS] << 4;
        cycl += 18;
    }

    // JMP Mp
    private final void opJmpFarInd() throws InvalidOpcodeException {
        if ((insnaddr & 0x10000) != 0)
            throw new InvalidOpcodeException("Register operand not allowed");
        int b = sreg[insnseg] << 4;
        jumpip = mem.loadWord(b + insnaddr);
        sreg[sregCS] = mem.loadWord(b + insnaddr + 2);
        csbase = sreg[sregCS] << 4;
        cycl += 24;
    }

    // JMP Ev
    private final void opJmpNearInd() {
        jumpip = loadWord();
        cycl += 11;
    }

    // JMP J
    private final void opJmpRel(int disp) {
        jumpip = (nextip + disp) & 0xffff;
        cycl += 15;
    }

    // LEA Gv,M
    private final void opLea(Operation op) throws InvalidOpcodeException {
        decodeModRm(op.modrm);
        if ((insnaddr & 0x10000) != 0)
            throw new InvalidOpcodeException("Register operand not allowed");
        reg[insnreg] = insnaddr;
        cycl += 2;
    }

    // LxS Gv,Mp
    private final void opLoadPtr(Operation op, int s) throws InvalidOpcodeException {
        decodeModRm(op.modrm);
        if ((insnaddr & 0x10000) != 0)
            throw new InvalidOpcodeException("Register operand not allowed");
        int b = sreg[insnseg] << 4;
        reg[insnreg] = mem.loadWord(b + insnaddr);
        sreg[s] = mem.loadWord(b + insnaddr + 2);
        cycl += 16;
    }

    // LOOP Jb
    private final void opLoop(boolean cond) {
        int disp = (byte) getImmByte();
        int c = (reg[regCX] - 1) & 0xffff;
        reg[regCX] = c;
        cycl += 5;
        if (c != 0 && cond) {
            jumpip = (nextip + disp) & 0xffff;
            cycl += 13;
        }
    }

    // MOV AL,Ob
    private final void opMovBAccMem() {
        if (insnseg == -1) insnseg = sregDS;
        insnaddr = getImmWord();
        reg[regAX] = (reg[regAX] & 0xff00) | loadByte();
        cycl += 2;
    }

    // MOV AX,Ov
    private final void opMovWAccMem() {
        if (insnseg == -1) insnseg = sregDS;
        insnaddr = getImmWord();
        reg[regAX] = loadWord();
        cycl += 2;
    }

    // MOV Ob,AL
    private final void opMovBMemAcc() {
        if (insnseg == -1) insnseg = sregDS;
        insnaddr = getImmWord();
        storeByte(reg[regAX]);
        cycl += 2;
    }

    // MOV Ov,AX
    private final void opMovWMemAcc() {
        if (insnseg == -1) insnseg = sregDS;
        insnaddr = getImmWord();
        storeWord(reg[regAX]);
        cycl += 2;
    }

    private final void opMulB() {
        int x = reg[regAX] & 0xff;
        int y = loadByte();
        int z = x * y;
        reg[regAX] = z & 0xffff;
        if ((z & 0xff00) != 0)
            flags |= (flCF|flOF);
        else
            flags &= ~ (flCF|flOF);
        cycl += 70;
    }

    private final void opMulW() {
        int x = reg[regAX];
        int y = loadWord();
        int z = x * y;
        reg[regAX] = z & 0xffff;
        reg[regDX] = (z >> 16) & 0xffff;
        if ((z & 0xffff0000) != 0)
            flags |= (flCF|flOF);
        else
            flags &= ~ (flCF|flOF);
        cycl += 118;
    }

    private final void opRetFar(int c) {
        jumpip = opPopW();
        sreg[sregCS] = opPopW();
        csbase = sreg[sregCS] << 4;
        reg[regSP] = (reg[regSP] + c) & 0xffff;
        cycl += 26;
    }

    private final void opRetNear(int c) {
        jumpip = opPopW();
        reg[regSP] = (reg[regSP] + c) & 0xffff;
        cycl += 16;
    }

    // XCHG AX,reg
    private final void opXchgAX(int r) {
        int t = reg[regAX];
        reg[regAX] = reg[r];
        reg[r] = t;
        cycl += 3;
    }

    // XCHG Eb,Gb
    private final void opXchgB(Operation op) {
        decodeModRm(op.modrm);
        int t = getRegByte(insnreg);
        putRegByte(insnreg, loadByte());
        storeByte(t);
        cycl += 4;
    }

    // XCHG Ev,Gv
    private final void opXchgW(Operation op) {
        decodeModRm(op.modrm);
        int t = reg[insnreg];
        reg[insnreg] = loadWord();
        storeWord(t);
        cycl += 4;
    }

    private final void opXlatB() {
        int a = reg[regAX];
        if (insnseg == -1) insnseg = sregDS;
        int off = (reg[regBX] + (a & 0xff)) & 0xffff;
        a = (a & 0xff00) | mem.loadByte((sreg[insnseg] << 4) + off);
        reg[regAX] = a;
        cycl += 11;
    }

}

/* end */
