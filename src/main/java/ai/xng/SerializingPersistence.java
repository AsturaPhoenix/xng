package ai.xng;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class SerializingPersistence {
  private final Path root;

  public KnowledgeBase load() throws IOException, ClassNotFoundException, ClassCastException {
    final File file = root.toFile();
    try (final ObjectInputStream oin = new ObjectInputStream(new FileInputStream(file))) {
      val kb = (KnowledgeBase) oin.readObject();
      System.out.printf("Using persistence at %s.\n", file.getAbsolutePath());
      return kb;
    } catch (final FileNotFoundException e) {
      System.out.printf("Persistence not found at %s; creating new persistence.\n", file.getAbsolutePath());
      val kb = new KnowledgeBase();
      new LanguageBootstrap(kb).bootstrap();
      return kb;
    } catch (final ObjectStreamException e) {
      System.out.printf("Persistence not compatible at %s. If this is expected, delete the file.\n",
          file.getAbsolutePath());
      throw e;
    }
  }

  public void save(final KnowledgeBase kb) throws FileNotFoundException, IOException {
    try (final ObjectOutputStream oout = new ObjectOutputStream(new FileOutputStream(root.toFile()))) {
      oout.writeObject(kb);
    }
  }
}
