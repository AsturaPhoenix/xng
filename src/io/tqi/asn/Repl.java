package io.tqi.asn;

import java.io.Serializable;

import io.reactivex.Observable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class Repl implements AutoCloseable {
  private static final Serializable
    CHATLOG_ID = "chatlog",
    INPUT_ID = "input";
  
  @Getter
  private final KnowledgeBase kb;
  
  public void sendInput(final String input) {
    kb.advanceContext();
    kb.associate(CHATLOG_ID, INPUT_ID, input);
  }
  
  public Observable<String> output() {
    return null;
  }
  
  @Override
  public void close() {
    kb.close();
  }
}
