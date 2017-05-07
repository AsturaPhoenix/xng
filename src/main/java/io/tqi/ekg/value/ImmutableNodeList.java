package io.tqi.ekg.value;

import java.util.Arrays;
import java.util.Collection;

import com.google.common.collect.ImmutableList;

import io.tqi.ekg.Node;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

@RequiredArgsConstructor
public class ImmutableNodeList implements NodeList, ImmutableValue {
  private static final long serialVersionUID = -4914023240141297447L;
  
  public static ImmutableNodeList from(final Collection<? extends Node> nodeList) {
    return nodeList instanceof ImmutableNodeList ? (ImmutableNodeList)nodeList :
      new ImmutableNodeList(ImmutableList.copyOf(nodeList));
  }
  
  public static ImmutableNodeList from(final Node... nodes) {
    return from(Arrays.asList(nodes));
  }
  
  @Delegate
  private final ImmutableList<Node> value;
}
