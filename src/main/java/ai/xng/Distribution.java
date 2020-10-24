package ai.xng;

public interface Distribution {
  void set(float value, float weight);

  default void set(final float value) {
    set(value, 1);
  }

  void add(float value, float weight);

  default void reinforce(float weight) {
    add(getMode(), weight);
  }

  float generate();

  float getMax();

  float getMin();

  float getMode();

  float getWeight();
}
