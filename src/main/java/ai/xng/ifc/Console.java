package ai.xng.ifc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import ai.xng.KnowledgeBase;
import ai.xng.LanguageBootstrap;
import ai.xng.Scheduler;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Console {
  public static void main(String[] mainArgs) throws ClassNotFoundException,
      ClassCastException, IOException {
    // val persistence = new
    // SerializingPersistence(FileSystems.getDefault().getPath("persistence"));
    try (final KnowledgeBase kb = new KnowledgeBase()) {
      new LanguageBootstrap(kb);

      kb.rxOutput().buffer(kb.rxOutput().debounce(200,
          TimeUnit.MILLISECONDS)).map(x -> String.join("", x))
          .subscribe(System.out::print);

      val in = new BufferedReader(new InputStreamReader(System.in));

      while (true) {
        System.out.print("> ");
        final String input = in.readLine();
        kb.inputValue.setData(input);
        Scheduler.global.fastForwardUntilIdle();
      }
    }
  }
}
