package ai.xng.util;

import java.io.Serializable;
import java.lang.reflect.Method;

public class SerializedMethod implements Serializable {
  private static final long serialVersionUID = 1L;

  private final Class<?> clazz;
  private final String name;
  private final Class<?>[] parameterTypes;

  public SerializedMethod(final Method method) {
    clazz = method.getClass();
    name = method.getName();
    parameterTypes = method.getParameterTypes();
  }

  public Method deserialize() throws NoSuchMethodException {
    return clazz.getMethod(name, parameterTypes);
  }
}
