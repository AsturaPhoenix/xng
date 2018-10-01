package io.tqi.ekg;

import java.io.Serializable;
import java.util.Arrays;

import com.google.common.collect.ImmutableList;

import lombok.Value;

/**
 * A wrapper around {@link Throwable} that makes it suitable for a node value.
 */
@Value
public class ImmutableException implements Serializable {
	private static final long serialVersionUID = 1779829057332294762L;
	Class<? extends Throwable> type;
	String message;
	ImmutableList<String> stackTrace;
	ImmutableException cause;

	public ImmutableException(final Throwable exception) {
		type = exception.getClass();
		message = exception.getMessage();
		stackTrace = Arrays.stream(exception.getStackTrace())
				.map(Object::toString).<ImmutableList.Builder<String>>collect(ImmutableList::builder,
						ImmutableList.Builder::add, (a, b) -> a.addAll(b.build()))
				.build();
		cause = exception.getCause() == null ? null : new ImmutableException(exception.getCause());
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder(type.getName()).append(": ").append(message);
		for (final String stackTraceElement : stackTrace) {
			builder.append("\n\t").append(stackTraceElement);
		}
		if (cause != null) {
			builder.append("Caused by ").append(cause);
		}
		return builder.toString();
	}
}
