package ai.xng;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SerializingPersistence {
  private final Path root;

  public KnowledgeBase load() throws IOException, ClassNotFoundException, ClassCastException {
    try (final ObjectInputStream oin = new ObjectInputStream(new FileInputStream(root.toFile()))) {
      return (KnowledgeBase) oin.readObject();
    } catch (final FileNotFoundException e) {
      System.out.println("Persistence not found at " + root + "; creating new persistence.");
      return new KnowledgeBase();
    } catch (final ObjectStreamException e) {
      System.out.println(
          "Persistence not compatible at " + root + ". If this is expected, delete the file.");
      throw e;
    }
  }

  public void save(final KnowledgeBase kb) throws FileNotFoundException, IOException {
    try (final ObjectOutputStream oout =
        new ObjectOutputStream(new FileOutputStream(root.toFile()))) {
      oout.writeObject(kb);
    }
  }
}
