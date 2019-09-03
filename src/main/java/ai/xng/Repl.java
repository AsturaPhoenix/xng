package ai.xng;

import io.reactivex.Observable;
import ai.xng.KnowledgeBase.Bootstrap;
import ai.xng.KnowledgeBase.Common;
import lombok.Getter;

public class Repl {
  @Getter
  private final KnowledgeBase kb;

  private Context rootContext = new Context();

  public Repl(final KnowledgeBase kb) {
    this.kb = kb;
  }

  public Observable<String> rxOutput() {
    return kb.rxOutput();
  }

  public void sendInput(final String input) {
    kb.new Invocation(kb.node(), kb.node(Bootstrap.eval)).literal(kb.node(Common.value),
        kb.node(input)).node.activate(new Context(rootContext));
  }
}
