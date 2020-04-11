package ai.xng;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TestUtil {
	public static int getSerializedSize(final Serializable object) throws IOException {
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (final ObjectOutputStream oout = new ObjectOutputStream(bout)) {
			oout.writeObject(object);
		}
		return bout.size();
	}

	@SuppressWarnings("unchecked")
	public static <T extends Serializable> T serialize(final T object) throws IOException, ClassNotFoundException {
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (final ObjectOutputStream oout = new ObjectOutputStream(bout)) {
			oout.writeObject(object);
		}

		final ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
		try (final ObjectInputStream oin = new ObjectInputStream(bin)) {
			return (T) oin.readObject();
		}
	}

	@FunctionalInterface
	public static interface CheckedRunnable {
		void run() throws Exception;

		@SneakyThrows
		default void runUnchecked() {
			run();
		}
	}

	/**
	 * Wraps a checked lambda in {@link SneakyThrows}.
	 */
	public static Runnable unchecked(final CheckedRunnable checked) {
		return () -> checked.runUnchecked();
	}
}
