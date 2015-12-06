package jx86pc;

public abstract class Operation {
  
  final int b;
  final int modrm;
  
  int v, v1;

  public Operation(int b) {
    this.b = b;
    this.modrm = -1;
    this.init();
  }
  
  public Operation(int b, int modrm) {
    this.b = b;
    this.modrm = modrm;
    this.init();
  }

  protected abstract void init();

  public abstract void exec();

}
