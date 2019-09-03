package ai.xng.ifc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.util.concurrent.TimeUnit;
import ai.xng.KnowledgeBase;
import ai.xng.Repl;
import ai.xng.SerializingPersistence;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Console {
  public static void main(String[] mainArgs)
      throws ClassNotFoundException, ClassCastException, IOException {
    val persistence = new SerializingPersistence(FileSystems.getDefault().getPath("persistence"));
    final KnowledgeBase kb = persistence.load();
    val repl = new Repl(kb);

    repl.rxOutput().buffer(repl.rxOutput().debounce(1, TimeUnit.SECONDS))
        .subscribe(System.out::print);

    val in = new BufferedReader(new InputStreamReader(System.in));

    while (true) {
      System.out.print("> ");
      final String input = in.readLine();
      repl.sendInput(input);
    }
  }
}
