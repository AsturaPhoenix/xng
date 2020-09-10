package ai.xng;

import java.io.Serializable;

public class Cluster<T extends Node> implements Serializable {
  private static final long serialVersionUID = 1L;

  protected final RecencyQueue<T> activations = new RecencyQueue<>();
}
