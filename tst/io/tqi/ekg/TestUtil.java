package io.tqi.ekg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TestUtil {
	@SuppressWarnings("unchecked")
	public static <T extends Serializable> T serialize(final T object) throws IOException, ClassNotFoundException {
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (final ObjectOutputStream oout = new ObjectOutputStream(bout)) {
			oout.writeObject(object);
		}
		
		final ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
		try (final ObjectInputStream oin = new ObjectInputStream(bin)) {
			return (T)oin.readObject();
		}
	}
}
