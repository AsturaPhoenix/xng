package io.tqi.ekg;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.reactivex.internal.functions.Functions;
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
        } catch (final ObjectStreamException e) {
            System.out.println("Persistence not compatible at " + root + ". If this is expected, delete the file.");
            throw e;
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
        kb.rxChange().sample(SAMPLE_PERIOD.toMillis(), TimeUnit.MILLISECONDS).subscribe(x -> persistence.save(kb),
                Functions.ERROR_CONSUMER, () -> persistence.save(kb));
        return kb;
    }
}
