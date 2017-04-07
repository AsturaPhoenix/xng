package io.tqi.asn;

import static org.petitparser.parser.primitive.CharacterParser.anyOf;
import static org.petitparser.parser.primitive.CharacterParser.noneOf;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.primitive.StringParser;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class Repl {
  @AllArgsConstructor
  private static class Command {
    public final String description;
    public final Function<List<Object>, String> impl;
  }

  @RequiredArgsConstructor
  private static class GlobalIdentifier {
    public final Identifier identifier;
  }

  private static final Parser IDENTIFIER = CharacterParser
      .of(Character::isJavaIdentifierStart, "identifier expected")
      .seq(CharacterParser.of(Character::isJavaIdentifierPart, "identifier expected")
          .star())
      .flatten().map(Identifier::new).trim(),
      SCOPED_IDENTIFIER = StringParser.of("::").seq(IDENTIFIER).pick(1)
          .map(
              GlobalIdentifier::new)
          .or(IDENTIFIER),
      ESCAPE = anyOf("\\\""),
      STRING = CharacterParser.of('"')
          .seq(noneOf("\\\"").or(CharacterParser.of('\\').seq(ESCAPE).pick(1)).star()
              .map((List<Character> chars) -> chars.stream().map(Object::toString)
                  .collect(Collectors.joining())),
              CharacterParser.of('"'))
          .pick(1).trim(),
      COMMAND_PARSER = IDENTIFIER.seq(SCOPED_IDENTIFIER.or(STRING).star());

  private static final Serializable INPUT_ID = new Identifier("input");

  @Getter
  private final KnowledgeBase kb;
  /**
   * Ephemeral scope for convenience while demonstrating. This is reset upon new
   * input.
   */
  private Map<Serializable, Node> scope;

  private final Subject<String> commandOutput = PublishSubject.create();

  public Observable<String> commandOutput() {
    return commandOutput;
  }

  public Observable<String> rxOutput() {
    return kb.rxOutput();
  }

  private final SortedMap<String, Command> commands = new TreeMap<>();

  {
    commands.put("createNode", new Command("sets a property on a node", args -> {
      final Node parent, property, child;

      parent = resolveNode(args.get(0));
      property = resolveNode(args.get(1));
      child = resolveNode(args.get(2));
      parent.setProperty(property, child);

      return null;
    }));
    commands.put("help", new Command("displays this help message", args -> {
      final StringBuilder builder = new StringBuilder();
      for (final Entry<String, Command> command : commands.entrySet()) {
        builder.append("  ").append(command.getKey()).append("\n    ")
            .append(command.getValue().description).append("\n");
      }
      return builder.toString();
    }));
  }

  private Node resolveNode(final Object identifier) {
    if (identifier instanceof GlobalIdentifier) {
      return kb.getOrCreateNode(((GlobalIdentifier) identifier).identifier);
    } else if (identifier instanceof Identifier) {
      if (scope == null) {
        scope = new HashMap<>();
      }
      return scope.computeIfAbsent((Identifier) identifier, Node::new);
    } else {
      return kb.getOrCreateNode((Serializable) identifier);
    }
  }

  private void getHelp() {
    commandOutput.onNext(commands.get("help").impl.apply(Collections.emptyList()));
  }

  public void sendCommand(final String command) {
    final Result result = COMMAND_PARSER.parse(command);

    if (result.isFailure()) {
      commandOutput.onNext(String.format("Invalid command string \"%s\"", command));
      getHelp();
    } else {
      final List<Object> parts = result.get();
      final String directive = ((Identifier) parts.get(0)).getValue();
      final Command def = commands.get(directive);
      if (def == null) {
        commandOutput.onNext(String.format("Unknown command \"%s\"", directive));
        getHelp();
      } else {
        commandOutput.onNext(def.impl.apply(parts.subList(1, parts.size())));
      }
    }
  }

  public void sendInput(final String input) {

  }
}
