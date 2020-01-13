package ai.xng;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Unimodal dynamic histogram optimized for performance (generation and
 * aggregation) rather than accuracy.
 */
public class Distribution implements Serializable {
	private static final long serialVersionUID = -3135118169738398742L;

	private final Random random;
	// sample => weight
	private final NavigableMap<Float, Float> densities = new TreeMap<>();
	// min and max represent the min and max values with nonzero density
	@Getter
	private float mode, min, max;
	private final ReadWriteLock lock = new ReentrantReadWriteLock(false);

	@AllArgsConstructor
	private static class InvCdfEntry implements Serializable {
		private static final long serialVersionUID = -1159688567915774709L;
		float cumulativeDensity;
		float value;
	}

	@Override
	public String toString() {
		lock.readLock().lock();
		try {
			return String.format("%.4g-%.4g-%.4g-%.4g-%.4g-%.4g-%.4g; %.4g(%.2f)", min, percentile(.15f),
					percentile(.3f), percentile(.5f), percentile(.7f), percentile(.85f), max, mode, getModeDensity());
		} finally {
			lock.readLock().unlock();
		}
	}

	private final List<InvCdfEntry> invCdf = new ArrayList<>();

	private interface Stepper {
		Entry<Float, Float> clampOutwards(float at);

		Entry<Float, Float> stepOutwards(float at);

		Entry<Float, Float> clampInwards(float at);

		Entry<Float, Float> stepInwards(float at);

		boolean isInRange(float at);

		/**
		 * Sets the distribution min or max (as appropriate) to this value.
		 * 
		 * @param zero a value where the density crosses zero
		 */
		void setZero(float zero);
	}

	private class LeftStepper implements Stepper {
		@Override
		public Entry<Float, Float> clampOutwards(final float at) {
			return densities.floorEntry(at);
		}

		@Override
		public Entry<Float, Float> stepOutwards(final float at) {
			return densities.lowerEntry(at);
		}

		@Override
		public Entry<Float, Float> clampInwards(final float at) {
			return densities.ceilingEntry(at);
		}

		@Override
		public Entry<Float, Float> stepInwards(final float at) {
			return densities.higherEntry(at);
		}

		@Override
		public boolean isInRange(final float at) {
			return at < mode;
		}

		@Override
		public void setZero(final float zero) {
			min = zero;
		}
	}

	private class RightStepper implements Stepper {
		@Override
		public Entry<Float, Float> clampOutwards(final float at) {
			return densities.ceilingEntry(at);
		}

		@Override
		public Entry<Float, Float> stepOutwards(final float at) {
			return densities.higherEntry(at);
		}

		@Override
		public Entry<Float, Float> clampInwards(final float at) {
			return densities.floorEntry(at);
		}

		@Override
		public Entry<Float, Float> stepInwards(final float at) {
			return densities.lowerEntry(at);
		}

		@Override
		public boolean isInRange(final float at) {
			return at > mode;
		}

		@Override
		public void setZero(final float zero) {
			max = zero;
		}
	}

	public Distribution() {
		this(0);
	}

	public Distribution(final float mode) {
		this(new Random(), mode);
	}

	public Distribution(final Random random, final float mode) {
		this.random = random;
		this.mode = mode;
		clear();
	}

	public void clear() {
		lock.writeLock().lock();
		try {
			densities.clear();
			densities.put(Float.NEGATIVE_INFINITY, 0f);
			densities.put(Float.POSITIVE_INFINITY, 0f);
			min = max = mode;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void add(final float value, final float weight) {
		if (weight == 0)
			return;

		lock.writeLock().lock();
		try {
			if (value == mode) {
				if (weight > 0) {
					addToMode(weight);
				} else {
					subtractFromMode(weight);
				}
			} else {
				final Stepper stepper = value > mode ? new RightStepper() : new LeftStepper();
				if (weight > 0) {
					addToSide(weight, value, stepper);
				} else {
					subtractFromSide(weight, value, stepper);
				}
			}

			if ((value >= min || value <= max) && min != max) {
				generateInvCdf();
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	private float getModeDensity() {
		return densities.floorEntry(mode).getValue();
	}

	private void addToMode(final float weight) {
		densities.put(mode, getModeDensity() + weight);
	}

	private void updateBisectedMode() {
		final float newMode = (densities.floorKey(mode) + densities.ceilingKey(mode)) / 2;
		if (Float.isFinite(newMode)) {
			mode = newMode;
		}
	}

	private void subtractFromMode(final float weight) {
		final float wNew = getModeDensity() + weight;

		final boolean leftCut = subtractOutwards(wNew, mode, new LeftStepper());
		final boolean rightCut = subtractOutwards(wNew, mode, new RightStepper());

		if (leftCut && rightCut) {
			densities.remove(mode);
		} else {
			densities.put(mode, wNew);
		}

		if (leftCut || rightCut) {
			updateBisectedMode();
		}

		// If the mode goes negative, this distribution becomes degenerate.
		if (wNew <= 0) {
			min = max = mode;
		}
	}

	private void addToSide(final float weight, final float value, final Stepper stepper) {
		final boolean wasDegenerate = getModeDensity() <= 0;

		final float w0 = stepper.clampOutwards(value).getValue();
		final float wNew = w0 + weight;
		densities.put(value, wNew);

		if (w0 <= 0 && wNew > 0) {
			stepper.setZero(value);
		}

		Entry<Float, Float> next = stepper.stepInwards(value);
		while (next.getValue() <= wNew && stepper.isInRange(next.getKey())) {
			densities.remove(next.getKey());
			densities.put(value, wNew);
			next = stepper.stepInwards(next.getKey());
		}

		if (next.getValue() <= wNew) {
			if (next.getValue() == wNew) {
				mode = (next.getKey() + value) / 2;
			} else {
				mode = value;
			}
			if (wasDegenerate) {
				min = max = mode;
			}
		}
	}

	private void subtractFromSide(final float weight, final float value, final Stepper stepper) {
		final float w0 = stepper.clampOutwards(value).getValue();
		final boolean wasMode = getModeDensity() == w0;
		final float wNew = w0 + weight;
		densities.put(value, wNew);

		if (w0 > 0 && wNew <= 0) {
			stepper.setZero(value);
		}

		if (subtractOutwards(wNew, value, stepper) && wasMode) {
			updateBisectedMode();
		}
	}

	private boolean subtractOutwards(final float max, final float startAfter, final Stepper stepper) {
		Entry<Float, Float> next = stepper.stepOutwards(startAfter);
		if (next == null || next.getValue() < max) {
			// subtracting outwards from infinity falls under this case
			return false;
		}

		float prev = next.getKey();
		next = stepper.stepOutwards(prev);
		while (next != null && next.getValue() >= max) {
			densities.remove(prev);
			prev = next.getKey();
			next = stepper.stepOutwards(prev);
		}

		densities.put(prev, max);
		return true;
	}

	private void generateInvCdf() {
		invCdf.clear();
		float cumulativeDensity = 0;
		for (Entry<Float, Float> next = densities.floorEntry(min); next.getKey() <= mode; next = densities
				.higherEntry(next.getKey())) {
			invCdf.add(new InvCdfEntry(cumulativeDensity, next.getKey()));
			cumulativeDensity += next.getValue();
		}
		// If the mode isn't actually in the ~pdf, this would be a no-op anyway
		// since it would exactly bisect the sides.
		if (densities.containsKey(mode)) {
			invCdf.add(new InvCdfEntry(cumulativeDensity, mode));
		}
		for (Entry<Float, Float> next = densities.higherEntry(mode); next.getKey() <= max; next = densities
				.higherEntry(next.getKey())) {
			cumulativeDensity += next.getValue();
			invCdf.add(new InvCdfEntry(cumulativeDensity, next.getKey()));
		}

		for (final InvCdfEntry entry : invCdf) {
			entry.cumulativeDensity /= cumulativeDensity;
		}
	}

	public float generate() {
		// This doesn't actually ever generate max, but we can live with that.
		return percentile(random.nextFloat());
	}

	/**
	 * @param percentile a percentile in the range [0, 1]
	 */
	public float percentile(float percentile) {
		lock.readLock().lock();
		try {
			if (min == max) {
				return mode;
			}

			int i = Collections.binarySearch(invCdf, new InvCdfEntry(percentile, 0),
					(a, b) -> Float.compare(a.cumulativeDensity, b.cumulativeDensity));
			if (i > 0) {
				return invCdf.get(i).value;
			} else {
				final InvCdfEntry lower = invCdf.get(-i - 2);
				final InvCdfEntry upper = invCdf.get(-i - 1);
				final float interp = (percentile - lower.cumulativeDensity)
						/ (upper.cumulativeDensity - lower.cumulativeDensity);
				return lower.value + interp * (upper.value - lower.value);
			}
		} finally {
			lock.readLock().unlock();
		}
	}
}
