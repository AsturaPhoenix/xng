package ai.xng;

public interface Distribution {
  static final float DEFAULT_WEIGHT = 10;

  void set(float value, float weight);

  default void set(final float value) {
    set(value, DEFAULT_WEIGHT);
  }

  void add(float value, float weight);

  float generate();

  float getMax();

  float getMin();

  float getMode();
}
