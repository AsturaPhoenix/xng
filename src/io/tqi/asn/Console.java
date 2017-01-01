package io.tqi.asn;

import java.io.IOException;
import java.nio.file.FileSystems;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Console {
  public static void main(String[] args) throws ClassNotFoundException, ClassCastException, IOException {
    try (
        final Repl repl = new Repl(SerializingPersistence.loadBound(FileSystems.getDefault().getPath("persistence")))) {
      repl.output().subscribe(s -> System.console().format("* %s", s));
      
      while (true) {
        final String input = System.console().readLine("> ");
        if (input.startsWith(":")) {
          final String command = input.substring(1).trim(), result;
          switch (command) {
            default:
              result = String.format("Unknown command \"%s\"", command);
          }
          System.console().printf("! %s", result);
        } else {
          repl.sendInput(input);
        }
      }
    }
  }
}
