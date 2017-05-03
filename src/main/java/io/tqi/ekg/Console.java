package io.tqi.ekg;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.concurrent.TimeUnit;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Console {
  public static void main(String[] mainArgs)
      throws ClassNotFoundException, ClassCastException, IOException {
	System.out.println("Start low-level commands with ';'.\n"
			+ "Output is denoted by '*'.\n"
			+ "System command output is denoted by '!'.");
	  
    final KnowledgeBase kb = SerializingPersistence
        .loadBound(FileSystems.getDefault().getPath("persistence"));
    final Repl repl = new Repl(kb);
    repl.commandOutput().subscribe(s -> System.console().format("! %s\n", s));
    repl.rxOutput().buffer(repl.rxOutput().debounce(1, TimeUnit.SECONDS))
        .subscribe(s -> System.console().format("* %s\n", s));

    while (true) {
      final String input = System.console().readLine("> ");
      if (input.startsWith(";")) {
        repl.sendCommand(input.substring(1));
      } else {
        repl.sendInput(input);
      }
    }
  }
}
