package io.tqi.asn;

import java.io.Serializable;
import java.util.concurrent.ConcurrentLinkedDeque;

import lombok.RequiredArgsConstructor;

public class NodeRelation implements Serializable {
  @RequiredArgsConstructor
  public static class Entry implements Serializable {
    private static final long serialVersionUID = -8594626313419596590L;
    
    public final Node child;
    public final long timestamp;
  }
  
  private static final long serialVersionUID = -983443632225229915L;
  
  private final ConcurrentLinkedDeque<Entry> history = new ConcurrentLinkedDeque<>();
  
  public void update(final Node latest, final long timestamp) {
    history.addFirst(new Entry(latest, timestamp));
  }
}
