package ai.xng.util;

import java.io.Serializable;

@FunctionalInterface
public interface SerializableSupplier<T> extends Serializable {
  T get();
}