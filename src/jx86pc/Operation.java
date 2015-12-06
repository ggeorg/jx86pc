package jx86pc;

public abstract class Operation {
  
  protected int modrm;
  protected int insnreg;

  public Operation() {
  }
  
  public Operation(int modrm) {
    this.modrm = modrm;
  }
  
  public Operation(int modrm, int insnreg) {
    this.modrm = modrm;
    this.insnreg = insnreg;
  }
  
  public abstract void exec();
  
}
