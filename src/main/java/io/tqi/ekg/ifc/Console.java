package io.tqi.ekg.ifc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.util.concurrent.TimeUnit;

import io.tqi.ekg.KnowledgeBase;
import io.tqi.ekg.Repl;
import io.tqi.ekg.SerializingPersistence;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Console {
    public static void main(String[] mainArgs) throws ClassNotFoundException, ClassCastException, IOException {
        System.out.println("Start low-level commands with ';'.\n" + "Output is denoted by '*'.");

        final KnowledgeBase kb = SerializingPersistence.loadBound(FileSystems.getDefault().getPath("persistence"));
        final Repl repl = new Repl(kb);
        repl.commandOutput().subscribe(s -> System.out.format("! %s\n", s));
        repl.rxOutput().buffer(repl.rxOutput().debounce(1, TimeUnit.SECONDS))
                .subscribe(s -> System.out.format("* %s\n", String.join("", s)));

        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("> ");
            final String input = in.readLine();
            if (input.startsWith(";")) {
                repl.execute(input.substring(1));
            } else {
                repl.sendInput(input);
            }
        }
    }
}
