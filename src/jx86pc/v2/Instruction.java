package jx86pc.v2;

public interface Instruction {
    boolean exec(int opcode, int modrm);
}
