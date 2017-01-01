package io.tqi.asn;

import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class Node implements Serializable {
  private static final long serialVersionUID = -4340465118968553513L;

  private final Serializable value;
  
  /**
   * indexed by (nullable) relation
   */
  private final ConcurrentMap<Optional<Node>, NodeRelation> associations = new ConcurrentHashMap<>();
  
  public void associate(final Optional<Node> relation, final Node child, final long timestamp) {
    associations.computeIfAbsent(relation, k -> new NodeRelation()).update(child, timestamp);
  }
}
