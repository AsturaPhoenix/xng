package ai.xng;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;

import ai.xng.util.SerializedMethod;
import lombok.Getter;

public interface DataNode extends Posterior {
  Object getData();

  class SerializableOrProxy<T> implements Serializable {
    @Getter
    private T data;

    public SerializableOrProxy(final T data) {
      if (data instanceof Serializable || data instanceof Method) {
        this.data = data;
      } else {
        throw new IllegalArgumentException("Data must have a serialized form.");
      }
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
      if (data instanceof Serializable) {
        o.writeObject(data);
      } else if (data instanceof Method method) {
        o.writeObject(new SerializedMethod(method));
      } else {
        throw new NotSerializableException();
      }
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      data = (T) o.readObject();
      if (data instanceof SerializedMethod proxy) {
        try {
          data = (T) proxy.deserialize();
        } catch (final NoSuchMethodException e) {
          throw new IOException(e);
        }
      }
    }
  }

  class MaybeTransient<T> implements Serializable {
    public T data;

    private void writeObject(final ObjectOutputStream o) throws IOException {
      if (data instanceof Serializable) {
        o.writeObject(data);
      } else if (data instanceof Method method) {
        o.writeObject(new SerializedMethod(method));
      } else {
        o.writeObject(null);
      }
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      data = (T) o.readObject();
      if (data instanceof SerializedMethod proxy) {
        try {
          data = (T) proxy.deserialize();
        } catch (final NoSuchMethodException e) {
          throw new IOException(e);
        }
      }
    }
  }
}
