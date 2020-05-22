package ai.xng;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import ai.xng.KnowledgeBase.Bootstrap;
import ai.xng.KnowledgeBase.Common;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Getter;
import lombok.val;

public class Repl {
  @Getter
  private final KnowledgeBase kb;

  private final Context rootContext;

  private final Subject<Throwable> rxError = PublishSubject.create();

  public Repl(final KnowledgeBase kb) {
    this.kb = kb;
    rootContext = kb.newContext();
  }

  public Observable<String> rxOutput() {
    return kb.rxOutput();
  }

  public Observable<Throwable> rxError() {
    return rxError;
  }

  public Future<Void> sendInput(final String input) {
    val completion = new CompletableFuture<Void>();
    val invocation = kb.new InvocationNode(kb.node(Bootstrap.eval)).literal(kb.node(Common.value), kb.node(input));
    val subscription = invocation.rxActivate().subscribe(__ -> completion.complete(null));
    rootContext.exceptionHandler = t -> {
      if (!completion.completeExceptionally(t)) {
        rxError.onNext(t);
      }
    };
    invocation.activate(rootContext);
    completion.whenComplete((__, t) -> subscription.dispose());
    return completion;
  }
}
