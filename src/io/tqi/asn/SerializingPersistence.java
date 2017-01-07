package io.tqi.asn;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SerializingPersistence {
  private static final Duration SAMPLE_PERIOD = Duration.ofSeconds(5);

  private final Path root;

  private KnowledgeBase load() throws IOException, ClassNotFoundException, ClassCastException {
    try (final ObjectInputStream oin = new ObjectInputStream(new FileInputStream(root.toFile()))) {
      return (KnowledgeBase) oin.readObject();
    } catch (final FileNotFoundException e) {
      System.out.println("Persistence not found at " + root + "; creating new persistence.");
      return new KnowledgeBase();
    }
  }

  private void save(final KnowledgeBase kb) throws FileNotFoundException, IOException {
    try (final ObjectOutputStream oout = new ObjectOutputStream(new FileOutputStream(root.toFile()))) {
      oout.writeObject(kb);
    }
  }

  public static KnowledgeBase loadBound(final Path root)
      throws FileNotFoundException, ClassNotFoundException, ClassCastException, IOException {
    final SerializingPersistence persistence = new SerializingPersistence(root);
    final KnowledgeBase kb = persistence.load();
    kb.change().sample(SAMPLE_PERIOD.toMillis(), TimeUnit.MILLISECONDS).subscribe(x -> persistence.save(kb), null,
        () -> persistence.save(kb));
    return kb;
  }
}
