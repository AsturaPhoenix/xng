package ai.xng;

public interface Distribution {
	void set(final float value);

	void add(final float value, final float weight);

	float generate();

	float getMax();

	float getMin();

	float getMode();
}
