package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;

import ai.xng.util.SerializedMethod;

public interface DataNode extends Posterior {
  Object getData();

  class MaybeTransient implements Serializable {
    private static final long serialVersionUID = 1L;

    public Object data;

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
      data = o.readObject();
      if (data instanceof SerializedMethod proxy) {
        try {
          data = proxy.deserialize();
        } catch (final NoSuchMethodException e) {
          throw new IOException(e);
        }
      }
    }
  }
}
