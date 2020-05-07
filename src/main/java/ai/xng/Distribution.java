package ai.xng;

public interface Distribution {
	static final float DEFAULT_WEIGHT = 10;

	void set(final float value, final float weight);

	default void set(final float value) {
		set(value, DEFAULT_WEIGHT);
	}

	void add(final float value, final float weight);

	float generate();

	float getMax();

	float getMin();

	float getMode();
}
