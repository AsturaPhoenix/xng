package io.tqi.ekg.value;

import lombok.Value;

@Value
public class StringValue implements ImmutableValue {
  private static final long serialVersionUID = 6592218671975646655L;
  String value;

  @Override
  public String toString() {
    return value;
  }
}
