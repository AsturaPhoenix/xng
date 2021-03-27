package ai.xng;

public class StmCluster extends BiCluster {
  public final Node address = new Node("address");

  public StmCluster(final DataCluster identifierCluster) {
    super(identifierCluster, null);
  }

  public StmCluster(final DataCluster identifierCluster, final String comment) {
    super(identifierCluster, comment);
  }
}
