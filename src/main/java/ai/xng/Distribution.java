package ai.xng;

public interface Distribution {
	void clear();

	void add(final float value, final float weight);

	float generate();

	float getMax();

	float getMin();

	float getMode();
}
