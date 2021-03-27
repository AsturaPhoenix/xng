package ai.xng;

public class StmCluster extends BiCluster {
  public final Node address = new Node("address");

  public StmCluster() {
  }

  public StmCluster(final String comment) {
    super(comment);
  }
}
