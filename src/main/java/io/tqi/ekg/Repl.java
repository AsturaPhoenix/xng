package io.tqi.ekg;

import static org.petitparser.parser.primitive.CharacterParser.anyOf;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.noneOf;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Getter;
import lombok.Value;

public class Repl {
    @Value
    private static class Argument {
        Identifier parameter;
        Object value;
    }

    //@formatter:off
    private static final Parser
            RAW_IDENTIFIER = CharacterParser
                    .of(Character::isJavaIdentifierStart, "identifier expected").seq(
                            CharacterParser.of(Character::isJavaIdentifierPart,
                                    "identifier expected").star())
                    .flatten(),
            IDENTIFIER = RAW_IDENTIFIER.seq(CharacterParser.of('.').seq(RAW_IDENTIFIER).star())
                    .flatten().map(Identifier::new).trim(),
            ESCAPE = anyOf("\\\""),
            STRING = CharacterParser.of('"')
                    .seq(noneOf("\\\"").or(CharacterParser.of('\\').seq(ESCAPE).pick(1)).star()
                            .map((List<Character> chars) -> chars.stream().map(Object::toString)
                                    .collect(Collectors.joining())),
                            CharacterParser.of('"'))
                    .pick(1).trim(),
            LEADING_DIGIT = CharacterParser.range('1', '9'),
            INTEGER = digit().or(LEADING_DIGIT.seq(digit().plus())),
            NUMBER = INTEGER.flatten().map((String s) -> Integer.parseInt(s)).or(
                    INTEGER.seq(CharacterParser.of('.'), digit().plus()).map(Double::parseDouble)).trim(),
            VALUE = IDENTIFIER.or(STRING, NUMBER),
            ARGUMENT = IDENTIFIER.seq(CharacterParser.of(':'), VALUE).map((List<Object> value) ->
                    new Argument((Identifier)value.get(0), value.get(2))),
            COMMAND = IDENTIFIER.seq(VALUE.or(ARGUMENT.star())),
            ALIAS = IDENTIFIER.seq(CharacterParser.of('='), COMMAND).map((List<Object> value) ->
                    new AbstractMap.SimpleEntry<>(value.get(0), value.get(2))),
            COMMAND_PARSER = ALIAS.or(COMMAND).end();
    //@formatter:on

    private static final Identifier INPUT_ID = new Identifier("input");

    @Getter
    private final KnowledgeBase kb;

    private final Node commandOutputNode;
    private final Subject<String> commandOutput = PublishSubject.create();

    public Repl(final KnowledgeBase kb) {
        this.kb = kb;

        commandOutputNode = kb.node("Repl.commandOutput");
        commandOutputNode.rxActivate().subscribe(t -> {
            final Node retval = commandOutputNode.getProperty(kb.ARGUMENT);
            if (retval != null && retval.getValue() != null)
                commandOutput.onNext(retval.getValue().toString());
        });
    }

    public Observable<String> commandOutput() {
        return commandOutput;
    }

    public Observable<String> rxOutput() {
        return kb.rxOutput();
    }

    private Node resolveNode(final Object token) {
        if (token instanceof Identifier) {
            return kb.getNode((Identifier) token);
        } else if (token instanceof String) {
            return kb.valueNode((String) token);
        } else if (token instanceof Number) {
            return kb.valueNode((Number) token);
        } else {
            throw new IllegalArgumentException("Unable to resolve a node for token type " + token.getClass());
        }
    }

    public void execute(final String command) {
        final Result result = COMMAND_PARSER.parse(command);

        if (result.isFailure()) {
            commandOutput.onNext(String.format("Invalid command string \"%s\"", command));
        } else {
            if (result.get() instanceof Map.Entry) {
                final Map.Entry<?, ?> alias = (Map.Entry<?, ?>) result.get();
                final Node cb = kb.node();
                cb.rxActivate().subscribe(t -> {
                    kb.indexNode((Identifier) alias.getKey(), cb.getProperty(kb.ARGUMENT));
                });
                executeCommand((List<?>) alias.getValue(), cb);
            } else {
                executeCommand(result.get(), commandOutputNode);
            }
        }
    }

    private void executeCommand(final List<?> parts, final Node callback) {
        final Node execute = kb.getNode((Identifier) parts.get(0));
        if (execute == null) {
            commandOutput.onNext(String.format("Unknown command \"%s\"", parts.get(0)));
        } else {
            final Node arg;

            if (parts.get(1) instanceof List) {
                arg = kb.node();
            } else {
                arg = resolveNode(parts.get(1));
            }
            kb.invoke(execute, arg, callback);
        }
    }

    public void sendInput(final String input) {
        kb.node(INPUT_ID).setProperty(kb.ARGUMENT, kb.valueNode(input)).activate();
    }
}
