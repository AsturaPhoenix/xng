package io.tqi.ekg;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class Util {
	public double add(final double a, final double b) {
		return a + b;
	}

	public void conditional(final boolean condition, final Node ifTrue, final Node ifFalse) {
		if (condition) {
			if (ifTrue != null)
				ifTrue.activate();
		} else {
			if (ifFalse != null)
				ifFalse.activate();
		}
	}
}
