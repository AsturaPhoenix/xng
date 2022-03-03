package ai.xng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Supplier;

import ai.xng.Cluster.PriorClusterProfile;
import ai.xng.constructs.BooleanDecoder;
import ai.xng.constructs.CharacterDecoder;
import ai.xng.constructs.CoincidentEffect;
import lombok.AllArgsConstructor;
import lombok.val;

public class LanguageBootstrap {
  private final KnowledgeBase kb;

  private Sequence dispatchSequence(final Prior start) {
    assert start.getCluster() != kb.execution;
    return directSequence(start.then(kb.execution.new Node()));
  }

  private Sequence directSequence(final BiNode start) {
    assert start.getCluster() == kb.execution;
    return new Sequence(start);
  }

  @AllArgsConstructor
  private class Sequence {
    BiNode tail;

    public Sequence thenDirect(final BiNode next, final IntegrationProfile profile) {
      tail.then(next, profile);
      return new Sequence(next);
    }

    public Sequence then(final Posterior... p) {
      for (val e : p) {
        tail.then(e);
      }
      tail = tail.then(kb.execution.new Node());
      return this;
    }

    public Sequence thenDelay() {
      tail = tail.then(kb.execution.new Node());
      return this;
    }

    public Sequence stanza() {
      return then(control.resetStanza)
          .thenDelay()
          .thenDelay();
    }

    public Sequence inhibit(final Posterior node, final IntegrationProfile profile) {
      tail.inhibit(node, profile);
      tail = tail.then(kb.execution.new Node());
      return this;
    }

    /**
     * Creates a conjunction on {@code condition}s via disinhibition. This
     * conjunction is robust against temporal summation. The query sequence operates
     * on a {@link IntegrationProfile#TRANSIENT} timing profile, while the
     * conditions disinhibit on a {@link IntegrationProfile#TWOGRAM} timing profile.
     */
    public Sequence disinhibit(final BiNode... conditions) {
      val query = tail;
      thenDelay().thenDelay();
      val action = tail;
      for (val condition : conditions) {
        query.then(kb.execution.new Node()).inhibitor(condition, IntegrationProfile.TWOGRAM).inhibit(action);
      }
      return this;
    }
  }

  private class Spawn {
    // TODO: manage eviction
    final Collection<Runnable> cleanup = new ArrayList<>();

    ActionCluster.Node spawner(final Supplier<Posterior> factory) {
      return kb.actions.new Node(() -> {
        val node = factory.get();
        val trigger = node.trigger();
        cleanup.add(() -> {
          trigger.clear();
          node.getTrace().evict(Scheduler.global.now());
        });
      });
    }

    final ActionCluster.Node stateRecognition = spawner(() -> kb.stateRecognition.new Node()),
        context = spawner(() -> kb.context.new Node()),
        binding = spawner(() -> kb.binding.new Node()),
        data = spawner(() -> kb.data.new MutableNode<>()),
        // Sequence recognition is a bit different in that we don't want to clear the
        // trace when we do stanza resets.
        sequenceRecognition = kb.actions.new Node(
            () -> cleanup.add(kb.sequenceRecognition.new Node().trigger()::clear));

    final ActionCluster.Node clearSpawns = kb.actions.new Node(() -> {
      cleanup.forEach(Runnable::run);
      cleanup.clear();
    });
  }

  private final Spawn spawn;

  public final class Control {
    public final StmCluster stackFrame = new StmCluster("stackFrame"),
        returnValue = new StmCluster("returnValue"),
        // A register for holding an object during qualified resolution.
        cxt = new StmCluster("cxt"),
        // An intermediate register for transfer operations.
        tmp = new StmCluster("tmp");

    // The following are fields on stack frame contexts.

    // statically scoped data, including the subroutine entrypoint and any static
    // variables. The context node here is a convenient way to refer to a particular
    // subroutine being called without invoking its entrypoint. Otherwise, it would
    // not be a simple matter to suppress invocation of the entrypoint and still use
    // it as a prior condition for other logic.
    public final BiCluster.Node staticContext = kb.naming.new Node("staticContext"),
        entrypoint = kb.naming.new Node("entrypoint"),
        arg1 = kb.naming.new Node("arg1"),
        // the calling context of a linked stack frame
        parent = kb.naming.new Node("parent"),
        // the entrypoint node to be invoked after restoring the calling context to the
        // call stack
        returnTo = kb.naming.new Node("returnTo");
    public final BiCluster.Node execute = kb.entrypoint.new Node("execute"),
        doReturn = kb.entrypoint.new Node("doReturn");

    // Stanza reset needs to be in a dedicated cluster since it tends to come after
    // captures and yet should usually not itself be the target of a capture.
    //
    // Capture operations tend to be punctuated by stanza resets. Stanza resets also
    // tend to occur after other invocation-like sequences.
    public final BiCluster controlCluster = new BiCluster();
    public final BiCluster.Node resetStanza = controlCluster.new Node();

    // Property binding is done through a dedicated layer of throwaway nodes to
    // separate disjunctions of conjunctions and honor correct associativity.
    //
    // This node will suppress any current binding, spawn a new binding node, and
    // capture it. It should be activated before the context and naming nodes to
    // serve as keys, and the keys and value should be separated by a pause for the
    // binding. The caller is responsible for binding the appropriate value cluster,
    // though it's conceivable that a future change would unify this.
    //
    // Event sequence:
    // * bindProperty entrypoint
    // * no-op (execution dispatch)
    // * context, naming, suppressPosteriors, spawn binding
    // * binding, capture binding
    // * value, capture value
    public final BiCluster.Node bindProperty = kb.entrypoint.new Node("bindProperty");

    private Control() {
    }

    private void setUp() {
      resetStanza.then(
          kb.resetOutboundPosteriors(kb.execution),
          kb.resetPosteriors(stackFrame),
          kb.resetPosteriors(returnValue),
          kb.resetPosteriors(cxt),
          kb.resetPosteriors(tmp),
          kb.resetPosteriors(kb.naming),
          kb.resetPosteriors(kb.context),
          kb.resetPosteriors(kb.entrypoint),
          kb.resetPosteriors(controlCluster),
          spawn.clearSpawns);

      dispatchSequence(execute)
          .stanza()
          .then(stackFrame.address)
          .then(staticContext)
          .thenDelay()
          .then(entrypoint);

      dispatchSequence(doReturn)
          // cxt = stackFrame
          .stanza()
          .then(cxt.address, stackFrame.address, kb.suppressPosteriors(cxt))
          .then(kb.capture(cxt, kb.context), kb.suppressPosteriors(kb.context))

          // stackFrame = cxt.Parent
          .stanza()
          .then(cxt.address)
          .then(parent)
          .then(stackFrame.address, kb.suppressPosteriors(stackFrame))
          .then(kb.capture(stackFrame, kb.context), kb.suppressPosteriors(kb.context))

          // parent/cxt.returnTo
          .stanza()
          .then(cxt.address)
          .then(returnTo);

      dispatchSequence(bindProperty)
          .then(kb.suppressPosteriors(kb.context), kb.suppressPosteriors(kb.naming), spawn.binding)
          .then(kb.capture(new PriorClusterProfile.ListBuilder()
              .add(kb.context)
              .add(kb.naming)
              .build(), kb.binding));
    }
  }

  public final Control control;

  private class StringIterator {
    final BiNode create = kb.entrypoint.new Node();
    /**
     * Node activated once a code point has been decoded.
     */
    final BiNode onNext = kb.execution.new Node();
    /**
     * Node that should be called once a longer processing operation is ready to
     * advance the iterator. It can also be inhibited by paths that are not ready to
     * proceed.
     */
    final BiNode advance = kb.entrypoint.new Node();
    final BooleanDecoder hasNextDecoder = new BooleanDecoder(kb.actions, kb.data, kb.input,
        data -> data instanceof Iterator<?> i ? Optional.of(i.hasNext()) : Optional.empty());
    // When hasNext is false, we'll often want to dispatch the next action based on
    // the static context.
    // TODO: There are probably better ways to do this.
    final BiCluster.Node terminalStaticContext = kb.execution.new Node();
    final BiNode codePoint = kb.naming.new Node();
    final InputCluster charCluster = new InputCluster();
    final CharacterDecoder charDecoder = new CharacterDecoder(kb.actions, kb.data, charCluster);

    void setUp() {
      val iterator = kb.naming.new Node();

      val iterator_in = new CoincidentEffect.Curry<>(kb.actions, kb.data);
      val iterator_out = new CoincidentEffect.Curry<>(kb.actions, kb.data);

      dispatchSequence(create)
          .stanza()
          .then(control.bindProperty)
          .then(control.stackFrame.address)
          .then(iterator)
          .then(spawn.data)
          .then(kb.capture(kb.binding, kb.data))

          .stanza()
          .then(control.stackFrame.address)
          .then(control.arg1)
          .thenDelay()
          .then(iterator_in.node)

          .stanza()
          .then(control.stackFrame.address)
          .then(iterator)
          .thenDelay()
          .then(iterator_out.node)
          .then(kb.actions.new Node(() -> ((DataCluster.MutableNode<Object>) iterator_out.require())
              .setData((((String) iterator_in.require().getData()).codePoints().iterator()))))

          .stanza()
          .then(control.bindProperty)
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(spawn.data)
          .then(kb.capture(kb.binding, kb.data))

          .then(advance);

      dispatchSequence(advance)
          .stanza()

          // It's not entirely clear if this is the right way to go about this, but it's
          // very valuable to be able to use persistent timing for most state recognition
          // while keeping it "scoped". The difficulty comes in separating this from state
          // that should not be scoped to a given iteration, and/or generalizing this to
          // different notions of scoping. Probably the most ideal way to do this would be
          // sustained activity, but using the simplified synapse model we have issues
          // with spike trains.
          .then(kb.resetPosteriors(kb.stateRecognition))

          .then(control.stackFrame.address)
          .then(iterator)
          .thenDelay()
          .then(hasNextDecoder.node);

      val next_in = new CoincidentEffect.Curry<>(kb.actions, kb.data);
      val next_out = new CoincidentEffect.Curry<>(kb.actions, kb.data);

      dispatchSequence(hasNextDecoder.isTrue)
          .stanza()
          .then(control.stackFrame.address)
          .then(iterator)
          .thenDelay()
          .then(next_in.node)

          .stanza()
          .then(control.stackFrame.address)
          .then(codePoint)
          .thenDelay()
          .then(next_out.node)
          .then(kb.actions.new Node(() -> {
            val chr = ((Iterator<Integer>) next_in.require().getData()).next();
            System.out.println(new StringBuilder().appendCodePoint(chr));
            ((DataCluster.MutableNode<Integer>) next_out.require())
                .setData(chr);
          }))

          .stanza()
          .then(control.stackFrame.address)
          .then(codePoint)
          .thenDelay()
          .then(charDecoder.node, onNext)
          // TODO: We might consider binding codePoint to a register first to avoid
          // polluted state during recognition.
          // Advance by default unless inhibited.
          .thenDirect(advance, IntegrationProfile.PERSISTENT);

      dispatchSequence(hasNextDecoder.isFalse)
          .stanza()
          .then(control.stackFrame.address)
          .then(control.staticContext)
          .thenDelay()
          .then(terminalStaticContext);
    }
  }

  private final StringIterator stringIterator;

  private class RecognitionClass {
    // TODO: formalize cluster or use state recognition
    final BiCluster.Node character = new BiCluster().new Node();
    // When we recognize a character, we'll often want to dispatch the next action
    // based on the static context.
    // TODO: There are probably better ways to do this.
    final BiCluster.Node staticContextQuery = kb.execution.new Node();

    void setUp() {
      // character recognition capture
      // We expect recognized characters to trigger a recognition tag two nodes deep,
      // with the first being the capture itself.
      val captureDispatch = stringIterator.onNext
          .then(kb.execution.new Node())
          .then(kb.execution.new Node()) // class recognition would trigger here
          .then(kb.execution.new Node());
      captureDispatch.inhibitor(character);
      captureDispatch.then(kb.actions.new Node(() -> {
        val capture = kb.stateRecognition.new Node();
        capture.then(character);
        capture.trigger();
      }));
      captureDispatch
          .then(kb.execution.new Node())
          .then(kb.capture(stringIterator.charCluster, kb.stateRecognition));

      dispatchSequence(character)
          .then(control.stackFrame.address)
          .then(control.staticContext)
          .thenDelay()
          .then(staticContextQuery);
    }
  }

  private final RecognitionClass recognitionClass;

  /**
   * This class modifies the InputIterator to capture a recognition conjunction
   * for every frame while active. It does not itself form an association from the
   * captured recognition.
   * <p>
   * Typical usage of this utility is to immediately bind the captured recognition
   * to a recognition circuit, which includes a familiarity tag, semantics, and
   * binding.
   */
  private class RecognitionSequenceMemorizer {
    final BiNode staticContext = kb.context.new Node("rsm"),
        entrypoint = kb.entrypoint.new Node("rsm/entrypoint");

    void setUp() {
      kb.binding.new Node().conjunction(staticContext, control.entrypoint).then(entrypoint);
      dispatchSequence(entrypoint)
          // TODO: It may be more idiomatic to create the capture later.
          .then(control.returnValue.address, kb.suppressPosteriors(control.returnValue), spawn.stateRecognition)
          .then(kb.capture(control.returnValue, kb.stateRecognition))
          .then(stringIterator.create);

      // Hook sequence capture up after character capture to avoid dealing with the
      // input conjunction directly.
      val capture = kb.execution.new Node();
      capture.conjunction(recognitionClass.staticContextQuery, staticContext);
      directSequence(capture)
          .then(spawn.sequenceRecognition)
          .then(kb.capture()
              .baseProfiles(IntegrationProfile.TWOGRAM)
              .priors(kb.sequenceRecognition)
              .priors(kb.stateRecognition)
              .posteriors(kb.sequenceRecognition));

      val captureReturn = kb.execution.new Node();
      captureReturn.conjunction(stringIterator.terminalStaticContext, staticContext);
      directSequence(captureReturn)
          .stanza()
          .then(control.returnValue.address)
          .then(kb.capture()
              .baseProfiles(IntegrationProfile.TWOGRAM)
              .priors(kb.sequenceRecognition)
              .posteriors(kb.stateRecognition))
          .then(control.doReturn);
    }
  }

  private final RecognitionSequenceMemorizer recognitionSequenceMemorizer;

  public final class Parse {
    public final BiNode staticContext = kb.context.new Node("parse"),
        entrypoint = kb.entrypoint.new Node("parse/entrypoint");

    public final BiCluster.Node constructionPointer = kb.naming.new Node("constructionPointer"),
        writePointer = kb.naming.new Node("writePointer");

    private Parse() {
    }

    private void setUp() {
      kb.binding.new Node().conjunction(staticContext, control.entrypoint).then(entrypoint);
      // One of the first things we should do when we begin parsing something is start
      // constructing a stack frame.
      dispatchSequence(entrypoint)
          .stanza()
          .then(control.bindProperty)
          .then(control.stackFrame.address)
          .then(constructionPointer)
          .then(spawn.context)
          .then(kb.capture(kb.binding, kb.context))
          .then(stringIterator.create);

      val print = kb.context.new Node("print");
      val printEntrypoint = kb.entrypoint.new Node("print/entrypoint");
      kb.binding.new Node().conjunction(print, control.entrypoint).then(printEntrypoint);
      dispatchSequence(printEntrypoint)
          .stanza()
          .then(control.stackFrame.address)
          .then(control.arg1)
          .thenDelay()
          .then(kb.print)
          .then(control.doReturn);

      val bindPrintEntrypoint = kb.entrypoint.new Node();
      dispatchSequence(bindPrintEntrypoint)
          .inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT)
          .stanza()
          .then(control.stackFrame.address)
          .then(constructionPointer)
          .then(control.cxt.address, kb.suppressPosteriors(control.cxt))
          .then(kb.capture(control.cxt, kb.context), kb.suppressPosteriors(kb.context))

          .stanza()
          .then(control.bindProperty)
          .then(control.cxt.address)
          .then(control.staticContext)
          .thenDelay()
          .then(print, kb.capture(kb.binding, kb.context), kb.suppressPosteriors(kb.context))

          // In the future we might like to scope the write pointer per construction
          // frame, but that story is not fleshed out yet so let's keep it simple for now.
          .stanza()
          .then(control.bindProperty)
          .then(control.stackFrame.address)
          .then(writePointer)
          .thenDelay()
          .then(control.arg1, kb.capture(kb.binding, kb.naming), kb.suppressPosteriors(kb.naming))
          .then(stringIterator.advance);

      val returnParseFrame = kb.execution.new Node();
      returnParseFrame.conjunction(stringIterator.terminalStaticContext, staticContext);
      directSequence(returnParseFrame)
          .stanza()
          .then(control.stackFrame.address)
          .then(constructionPointer)
          .then(control.returnValue.address, kb.suppressPosteriors(control.returnValue))
          .then(kb.capture(control.returnValue, kb.context), kb.suppressPosteriors(kb.context))
          .then(control.doReturn);

      {
        val call = kb.context.new Node("train: \"print(\"");
        kb.binding.new Node().conjunction(call, control.staticContext).then(recognitionSequenceMemorizer.staticContext);
        kb.data.new FinalNode<>("print(").conjunction(call, control.arg1);
        val bindBindPrint = kb.execution.new Node();
        kb.binding.new Node().conjunction(call, control.returnTo).then(bindBindPrint);
        directSequence(bindBindPrint)
            .then(control.returnValue.address)
            .thenDelay()
            .then(bindPrintEntrypoint, kb.capture(kb.stateRecognition, kb.entrypoint),
                kb.suppressPosteriors(kb.entrypoint));
        control.stackFrame.address.then(call);
        control.execute.activate();
        Scheduler.global.fastForwardUntilIdle();
        Scheduler.global.fastForwardFor(IntegrationProfile.PERSISTENT.period());
      }
    }
  }

  public final Parse parse;

  // Eval parses the argument and executes the resulting construct.
  public final class Eval {
    public final BiNode staticContext = kb.context.new Node("eval"),
        entrypoint = kb.entrypoint.new Node("eval/entrypoint");

    private Eval() {
    }

    private void setUp() {
      entrypoint.conjunction(staticContext, control.entrypoint);

      val executeParsed = kb.entrypoint.new Node("eval/executeParsed");
      dispatchSequence(executeParsed)
          // returnValue.parent = stackFrame
          .stanza()
          .then(control.bindProperty)
          .then(control.returnValue.address)
          .then(control.parent)
          .then(control.stackFrame.address)
          .then(kb.capture(kb.binding, kb.context), kb.suppressPosteriors(kb.context))

          // stackFrame = returnValue
          .stanza()
          .then(control.stackFrame.address, control.returnValue.address, kb.suppressPosteriors(control.stackFrame))
          .then(kb.capture(control.stackFrame, kb.context), kb.suppressPosteriors(kb.context))

          .stanza()
          .then(control.bindProperty)
          .then(control.stackFrame.address)
          .then(control.returnTo)
          .thenDelay()
          // Note that at some point, we have considered expanding suppressPosteriors to
          // also suppress the trace, for use in pulling down nodes after activation to
          // clear working memory. If we do end up needing that behavior, we will need to
          // reconcile against this use case where we suppress posteriors in order to
          // highlight a node's trace without side effects.
          .then(control.doReturn, kb.capture(kb.binding, kb.entrypoint), kb.suppressPosteriors(kb.entrypoint))
          .thenDelay()
          .then(control.execute);

      dispatchSequence(entrypoint)
          .stanza()
          .then(control.cxt.address, kb.suppressPosteriors(control.cxt), spawn.context)
          .then(kb.capture(control.cxt, kb.context), kb.suppressPosteriors(kb.context))

          .stanza()
          .then(control.bindProperty)
          .then(control.cxt.address)
          .then(control.staticContext)
          .thenDelay()
          .then(parse.staticContext, kb.capture(kb.binding, kb.context), kb.suppressPosteriors(kb.context))

          .stanza()
          .then(control.stackFrame.address)
          .then(control.arg1)
          .then(control.tmp.address, kb.suppressPosteriors(control.tmp))
          .then(kb.capture(control.tmp, kb.data))

          .stanza()
          .then(control.bindProperty)
          .then(control.cxt.address)
          .then(control.arg1)
          .then(control.tmp.address)
          .then(kb.capture(kb.binding, kb.data))

          .stanza()
          .then(control.bindProperty)
          .then(control.cxt.address)
          .then(control.returnTo)
          .thenDelay()
          .then(executeParsed, kb.capture(kb.binding, kb.entrypoint), kb.suppressPosteriors(kb.entrypoint))

          // cxt.parent = stackFrame
          .stanza()
          .then(control.bindProperty)
          .then(control.cxt.address)
          .then(control.parent)
          .then(control.stackFrame.address)
          .then(kb.capture(kb.binding, kb.context), kb.suppressPosteriors(kb.context))

          // stackFrame = cxt
          .stanza()
          .then(control.stackFrame.address, control.cxt.address, kb.suppressPosteriors(control.stackFrame))
          .then(kb.capture(control.stackFrame, kb.context), kb.suppressPosteriors(kb.context))

          .then(control.execute);
    }
  }

  public final Eval eval;

  private class StringLiteralBuilder {
    final BiNode isActive = kb.stateRecognition.new Node() {
      @Override
      public void activate() {
        System.out.println("isActive");
        super.activate();
      }
    };;

    void setUp() {
      val builder = new StringBuilder();

      val start = kb.execution.new Node() {
        @Override
        public void activate() {
          System.out.println("start");
          super.activate();
        }
      };
      val append = kb.execution.new Node() {
        @Override
        public void activate() {
          System.out.println("append");
          super.activate();
        }
      };
      val end = kb.execution.new Node() {
        @Override
        public void activate() {
          System.out.println("end");
          super.activate();
        }
      };

      val quote = kb.stateRecognition.new Node() {
        @Override
        public void activate() {
          System.out.println("quote");
          super.activate();
        }
      };
      val conjunction = new ConjunctionJunction();
      stringIterator.charDecoder.forOutput('"', conjunction::add);
      conjunction.build(quote).then(recognitionClass.character);

      new ConjunctionJunction()
          .add(quote, IntegrationProfile.TWOGRAM)
          .add(parse.staticContext)
          .build(start.inhibitor(isActive));
      new ConjunctionJunction()
          .add(quote, IntegrationProfile.TWOGRAM)
          .add(parse.staticContext)
          .add(isActive, IntegrationProfile.TWOGRAM)
          .build(end);

      directSequence(start)
          .inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT)
          .then(kb.actions.new Node(() -> builder.setLength(0)))
          .stanza()
          .then(control.stackFrame.address)
          .thenDelay()
          .then(isActive, kb.capture()
              .baseProfiles(IntegrationProfile.TWOGRAM) // This is needed to avoid hyperactivation over the 2-gram
                                                        // profile. Is this the right way to go about it?
                                                        // This doesn't work. We could do disinhibition...
              .priors(kb.context)
              .posteriors(kb.stateRecognition))
          .then(stringIterator.advance);

      end.inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT);
      directSequence(end)
          .stanza()
          .then(control.stackFrame.address)
          .thenDelay()
          .then(kb.capture(kb.context, kb.stateRecognition)).inhibit(isActive, IntegrationProfile.TRANSIENT)

          .stanza()
          .then(control.stackFrame.address)
          .then(parse.constructionPointer)
          .then(control.cxt.address, kb.suppressPosteriors(control.cxt))
          .then(kb.capture(control.cxt, kb.context), kb.suppressPosteriors(kb.context))

          .stanza()
          .then(control.stackFrame.address)
          .then(parse.writePointer)
          .then(control.tmp.address, kb.suppressPosteriors(control.tmp))
          .then(kb.capture(control.tmp, kb.naming), kb.suppressPosteriors(kb.naming))

          .stanza()
          .then(control.bindProperty)
          .then(control.tmp.address, control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> kb.data.new FinalNode<>(builder.toString()).trigger()))
          .then(kb.capture(kb.binding, kb.data))
          .then(stringIterator.advance);

      val notQuote = kb.stateRecognition.new Node() {
        @Override
        public void activate() {
          System.out.println("notQuote");
          super.activate();
        }
      };
      recognitionClass.character.then(notQuote).inhibitor(quote);
      new ConjunctionJunction()
          .add(notQuote, IntegrationProfile.TWOGRAM)
          .add(isActive, IntegrationProfile.TWOGRAM).build(append);
      append.inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT);
      directSequence(append)
          .stanza()
          .then(control.stackFrame.address)
          .then(stringIterator.codePoint)
          .thenDelay()
          .then(new CoincidentEffect.Lambda<>(kb.actions, kb.data, node -> {
            if (node.getData() instanceof Integer codePoint) {
              builder.appendCodePoint(codePoint);
            }
          }).node)
          .then(stringIterator.advance);
    }
  }

  private final StringLiteralBuilder stringLiteralBuilder;

  public LanguageBootstrap(final KnowledgeBase kb) {
    this.kb = kb;

    spawn = new Spawn();
    control = new Control();
    stringIterator = new StringIterator();
    recognitionClass = new RecognitionClass();
    recognitionSequenceMemorizer = new RecognitionSequenceMemorizer();
    parse = new Parse();
    eval = new Eval();
    stringLiteralBuilder = new StringLiteralBuilder();

    control.setUp();
    stringIterator.setUp();
    recognitionClass.setUp();
    recognitionSequenceMemorizer.setUp();
    parse.setUp();
    eval.setUp();
    stringLiteralBuilder.setUp();

    // When the input changes, we need to construct an eval call.
    dispatchSequence(kb.inputValue.onUpdate)
        // cxt = spawn.context
        .stanza()
        .then(control.cxt.address, kb.suppressPosteriors(control.cxt), spawn.context)
        .then(kb.capture(control.cxt, kb.context))

        // cxt.parent = stackFrame
        .stanza()
        .then(control.bindProperty)
        .then(control.cxt.address)
        .then(control.parent)
        .then(control.stackFrame.address)
        .then(kb.capture(kb.binding, kb.context), kb.suppressPosteriors(kb.context))

        // stackFrame = cxt
        .stanza()
        .then(control.stackFrame.address, kb.suppressPosteriors(control.stackFrame), control.cxt.address)
        .then(kb.capture(control.stackFrame, kb.context), kb.suppressPosteriors(kb.context))

        .stanza()
        .then(control.bindProperty)
        .then(control.stackFrame.address)
        .then(control.staticContext)
        .thenDelay()
        .then(eval.staticContext, kb.capture(kb.binding, kb.context), kb.suppressPosteriors(kb.context))

        .stanza()
        .then(control.bindProperty)
        .then(control.stackFrame.address)
        .then(control.arg1)
        .thenDelay()
        .then(kb.inputValue, kb.capture(kb.binding, kb.data))
        .then(control.execute);
  }
}
