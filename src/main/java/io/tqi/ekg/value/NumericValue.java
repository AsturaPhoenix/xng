package io.tqi.ekg.value;

import lombok.Value;

@Value
public class NumericValue implements ImmutableValue {
  private static final long serialVersionUID = -7594958369833142413L;
  Number value;

  @Override
  public String toString() {
    return value.toString();
  }
}
