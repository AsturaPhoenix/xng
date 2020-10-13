package ai.xng.util;

import java.util.function.Supplier;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class Util {
  public double add(final double a, final double b) {
    return a + b;
  }

  public <T> T[] generate(final T[] array, final Supplier<T> generator) {
    for (int i = 0; i < array.length; ++i) {
      array[i] = generator.get();
    }
    return array;
  }
}
