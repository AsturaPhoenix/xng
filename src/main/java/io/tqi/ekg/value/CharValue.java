package io.tqi.ekg.value;

import lombok.Value;

@Value
public class CharValue implements ImmutableValue {
  private static final long serialVersionUID = 6592218671975646655L;
  char value;

  @Override
  public String toString() {
    return Character.toString(value);
  }
}
