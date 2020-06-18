package ai.xng;

public class IntBuilder {
  private int n;

  public void append(final int codePoint) {
    if (codePoint < '0' || codePoint > '9') {
      throw new IllegalArgumentException(String.format("Code point %s is out of the range ['0', '9'].", codePoint));
    }
    n = 10 * n + codePoint - '0';
  }

  public int get() {
    return n;
  }

  public int getNegative() {
    return -n;
  }
}