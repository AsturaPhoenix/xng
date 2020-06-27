// package ai.xng.ifc;

// import java.io.BufferedReader;
// import java.io.IOException;
// import java.io.InputStreamReader;
// import java.nio.file.FileSystems;
// import java.util.concurrent.ExecutionException;
// import java.util.concurrent.TimeUnit;
// import ai.xng.KnowledgeBase;
// import ai.xng.Repl;
// import ai.xng.SerializingPersistence;
// import lombok.AccessLevel;
// import lombok.RequiredArgsConstructor;
// import lombok.val;

// @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
// public final class Console {
// public static void main(String[] mainArgs) throws ClassNotFoundException,
// ClassCastException, IOException {
// val persistence = new
// SerializingPersistence(FileSystems.getDefault().getPath("persistence"));
// try (final KnowledgeBase kb = persistence.load()) {
// val repl = new Repl(kb);

// repl.rxOutput().buffer(repl.rxOutput().debounce(200,
// TimeUnit.MILLISECONDS)).map(x -> String.join("", x))
// .subscribe(System.out::print);
// repl.rxError().subscribe(Throwable::printStackTrace);

// val in = new BufferedReader(new InputStreamReader(System.in));

// while (true) {
// System.out.print("> ");
// final String input = in.readLine();
// try {
// repl.sendInput(input).get();
// } catch (final ExecutionException e) {
// e.getCause().printStackTrace();
// } catch (final InterruptedException e) {
// return;
// }
// }
// }
// }
// }
